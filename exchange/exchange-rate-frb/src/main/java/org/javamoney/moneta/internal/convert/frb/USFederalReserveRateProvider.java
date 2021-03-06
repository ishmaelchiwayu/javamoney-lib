package org.javamoney.moneta.internal.convert.frb;

import java.io.InputStream;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.money.CurrencyUnit;
import javax.money.Monetary;
import javax.money.MonetaryException;
import javax.money.convert.ConversionContext;
import javax.money.convert.ConversionQuery;
import javax.money.convert.CurrencyConversionException;
import javax.money.convert.ExchangeRate;
import javax.money.convert.ProviderContext;
import javax.money.convert.ProviderContextBuilder;
import javax.money.convert.RateType;
import javax.money.spi.Bootstrap;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.javamoney.moneta.convert.ExchangeRateBuilder;
import org.javamoney.moneta.spi.AbstractRateProvider;
import org.javamoney.moneta.spi.DefaultNumberValue;
import org.javamoney.moneta.spi.LoaderService;
import org.javamoney.moneta.spi.LoaderService.LoaderListener;
/**
* <p>
* This class implements an {@link javax.money.convert.ExchangeRateProvider}
* that loads data from the Federal Reserve Bank of the United States RSS feed.
* The Fed publishes rates on its RSS feed for the prior week, Monday through Friday.
* This provider loads all available rates, purging from its cache any older rates
* with each re-load.
* </p>
*/
public class USFederalReserveRateProvider extends AbstractRateProvider implements LoaderListener {

    private static final Logger LOG = Logger.getLogger(USFederalReserveRateProvider.class.getName());

    private static final String DATA_ID = USFederalReserveRateProvider.class.getSimpleName();

    protected static final String BASE_CURRENCY_CODE = "USD";

    /**
     * Base currency of the loaded rates is always USD.
     */
    public static final CurrencyUnit BASE_CURRENCY = Monetary.getCurrency(BASE_CURRENCY_CODE);

    /**
     * The {@link ConversionContext} of this provider.
     */
    private static final ProviderContext CONTEXT = ProviderContextBuilder.of("FRB", RateType.HISTORIC)
            .set("providerDescription", "Federal Reserve Bank of the United States").build();

    /**
     * Historic exchange rates, rate timestamp as UTC long.
     */
    private final Map<LocalDate, Map<String, ExchangeRate>> rates = new ConcurrentHashMap<>();

    /**
     * Parser factory.
     */
    private final SAXParserFactory saxParserFactory = SAXParserFactory.newInstance();

    protected volatile String loadState;

    protected volatile CountDownLatch loadLock = new CountDownLatch(1);


    public USFederalReserveRateProvider() {
        super(CONTEXT);
        initalize();
    }

    public USFederalReserveRateProvider(ProviderContext providerContext) {
        super(providerContext);
        initalize();
    }
    private void initalize() {
        saxParserFactory.setNamespaceAware(false);
        saxParserFactory.setValidating(false);
        LoaderService loader = Bootstrap.getService(LoaderService.class);
        loader.addLoaderListener(this, getDataId());
        try {
            loader.loadDataAsync(getDataId());
        } catch(Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String getDataId() {
        return DATA_ID;
    }

    @Override
    public ExchangeRate getExchangeRate(ConversionQuery conversionQuery) {
        Objects.requireNonNull(conversionQuery);
        try {
            if (loadLock.await(30, TimeUnit.SECONDS)) {
                if (rates.isEmpty()) {
                    return null;
                }
                RateResult result = findExchangeRate(conversionQuery);
                ExchangeRateBuilder builder = getBuilder(conversionQuery, result.date);
                ExchangeRate sourceRate = result.targets.get(conversionQuery.getBaseCurrency().getCurrencyCode());
                ExchangeRate target = result.targets.get(conversionQuery.getCurrency().getCurrencyCode());
                return createExchangeRate(conversionQuery, builder, sourceRate, target);
            }else{
                throw new MonetaryException("Failed to load currency conversion data: " + loadState);
            }
        }
        catch(InterruptedException e){
            throw new MonetaryException("Failed to load currency conversion data: Load task has been interrupted.", e);
        }
    }

    private ExchangeRateBuilder getBuilder(ConversionQuery query, LocalDate localDate) {
        ExchangeRateBuilder builder = new ExchangeRateBuilder(getExchangeContext("frb.digit.fraction"));
        builder.setBase(query.getBaseCurrency());
        builder.setTerm(query.getCurrency());
        return builder;
    }

    @Override
    public void newDataLoaded(String resourceId, InputStream is) {
        final int oldSize = this.rates.size();
        try {
            Map<LocalDate, Map<String, ExchangeRate>> newRates = new HashMap<>();
            SAXParser parser = saxParserFactory.newSAXParser();
            parser.parse(is, new USFederalReserveRateReadingHandler(newRates, getContext()));

            //Remove any older rates so the map continually only has one week of rates cached
            Set<LocalDate> existingDates = new HashSet<>(rates.keySet());
            rates.putAll(newRates);
            for(LocalDate ld : existingDates) {
                if(!newRates.containsKey(ld)) {
                    rates.remove(ld);
                }
            }
            int newSize = this.rates==null?0:this.rates.size();
            loadState = "Loaded " + resourceId + " exchange rates for days:" + (newSize - oldSize);
            LOG.info(loadState);
        } catch (Exception e) {
            loadState = "Last Error during data load: " + e.getMessage();
            LOG.log(Level.FINEST, "Error during data load.", e);
        } finally{
            loadLock.countDown();
        }
    }

    private RateResult findExchangeRate(ConversionQuery conversionQuery) {
        LocalDate[] dates = getQueryDates(conversionQuery);
        if (dates == null) {
            Comparator<LocalDate> comparator = Comparator.naturalOrder();
            LocalDate date =
                this.rates
                    .keySet()
                    .stream()
                    .sorted(comparator.reversed())
                    .findFirst()
                    .orElseThrow(
                        () -> new MonetaryException("There is not more recent exchange rate to rate on " + getDataId()));
            return new RateResult(date, this.rates.get(date));
        } else {
            for (LocalDate localDate : dates) {
                Map<String, ExchangeRate> targets = this.rates.get(localDate);
                if (Objects.nonNull(targets)) {
                    return new RateResult(localDate, targets);
                }
            }
            String datesOnErros =
                Stream.of(dates).map(date -> date.format(DateTimeFormatter.ISO_LOCAL_DATE))
                    .collect(Collectors.joining(","));
            throw new MonetaryException("There is not exchange on day " + datesOnErros + " to rate to  rate on "
                + getDataId() + ".");
        }
    }

    private ExchangeRate createExchangeRate(ConversionQuery query, ExchangeRateBuilder builder,
        ExchangeRate sourceRate, ExchangeRate target) {

        if (areBothBaseCurrencies(query)) {
            builder.setFactor(DefaultNumberValue.ONE);
            return builder.build();
        } else if (BASE_CURRENCY_CODE.equals(query.getCurrency().getCurrencyCode())) {
            if (Objects.isNull(sourceRate)) {
                return null;
            }
            return reverse(sourceRate);
        } else if (BASE_CURRENCY_CODE.equals(query.getBaseCurrency().getCurrencyCode())) {
            return target;
        } else {

            ExchangeRate rate1 =
                getExchangeRate(query.toBuilder().setTermCurrency(Monetary.getCurrency(BASE_CURRENCY_CODE)).build());
            ExchangeRate rate2 =
                getExchangeRate(query.toBuilder().setBaseCurrency(Monetary.getCurrency(BASE_CURRENCY_CODE))
                    .setTermCurrency(query.getCurrency()).build());
            if (Objects.nonNull(rate1) && Objects.nonNull(rate2)) {
                builder.setFactor(multiply(rate1.getFactor(), rate2.getFactor()));
                builder.setRateChain(rate1, rate2);
                return builder.build();
            }
            throw new CurrencyConversionException(query.getBaseCurrency(), query.getCurrency(), sourceRate.getContext());
        }
    }

    private boolean areBothBaseCurrencies(ConversionQuery query) {
        return BASE_CURRENCY_CODE.equals(query.getBaseCurrency().getCurrencyCode())
            && BASE_CURRENCY_CODE.equals(query.getCurrency().getCurrencyCode());
    }

    protected static ExchangeRate reverse(ExchangeRate rate) {
        if (Objects.isNull(rate)) {
            throw new IllegalArgumentException("Rate null is not reversible.");
        }
        return new ExchangeRateBuilder(rate).setRate(rate).setBase(rate.getCurrency()).setTerm(rate.getBaseCurrency())
            .setFactor(divide(DefaultNumberValue.ONE, rate.getFactor(), MathContext.DECIMAL64)).build();
    }

    private class RateResult {
        private final LocalDate date;

        private final Map<String, ExchangeRate> targets;

        RateResult(LocalDate date, Map<String, ExchangeRate> targets) {
            this.date = date;
            this.targets = targets;
        }
    }

}