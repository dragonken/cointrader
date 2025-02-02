package org.cryptocoinpartners.module.xchange;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

import org.apache.commons.configuration.Configuration;
import org.cryptocoinpartners.module.Context;
import org.cryptocoinpartners.schema.Book;
import org.cryptocoinpartners.schema.BookFactory;
import org.cryptocoinpartners.schema.Exchange;
import org.cryptocoinpartners.schema.Listing;
import org.cryptocoinpartners.schema.Market;
import org.cryptocoinpartners.schema.MarketDataError;
import org.cryptocoinpartners.schema.Prompt;
import org.cryptocoinpartners.schema.TradeFactory;
import org.cryptocoinpartners.util.CompareUtils;
import org.cryptocoinpartners.util.EM;
import org.cryptocoinpartners.util.RateLimiter;
import org.cryptocoinpartners.util.XchangeUtil;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.xeiam.xchange.currency.CurrencyPair;
import com.xeiam.xchange.dto.Order.OrderType;
import com.xeiam.xchange.dto.marketdata.OrderBook;
import com.xeiam.xchange.dto.marketdata.Trade;
import com.xeiam.xchange.dto.marketdata.Trades;
import com.xeiam.xchange.dto.trade.LimitOrder;
import com.xeiam.xchange.okcoin.FuturesContract;
import com.xeiam.xchange.service.polling.marketdata.PollingMarketDataService;
import com.xeiam.xchange.service.streaming.ExchangeEvent;
import com.xeiam.xchange.service.streaming.ExchangeEventType;
import com.xeiam.xchange.service.streaming.ExchangeStreamingConfiguration;
import com.xeiam.xchange.service.streaming.StreamingExchangeService;

/**
 * @author Tim Olson
 */
@Singleton
public class XchangeData {

    @Inject
    public XchangeData(Context context, Configuration config, BookFactory bookFactory, TradeFactory tradeFactory) {
        this.context = context;
        this.bookFactory = bookFactory;
        this.tradeFactory = tradeFactory;
        final String configPrefix = "xchange";
        Set<String> exchangeTags = XchangeUtil.getExchangeTags();

        // now we have all the exchange tags.  process each config group
        for (String tag : exchangeTags) {
            // three configs required:
            // .class the full classname of the Xchange implementation
            // .rate.queries rate limit the number of queries to this many (default: 1)
            // .rate.period rate limit the number of queries during this period of time (default: 1 second)
            // .listings identifies which Listings should be fetched from this exchange
            Exchange exchange = XchangeUtil.getExchangeForTag(tag);
            if (exchange != null) {
                String prefix = configPrefix + "." + tag + '.';
                final String helperClassName = config.getString(prefix + "helper.class", null);
                final String streamingConfigClassName = config.getString(prefix + "streaming.config.class", null);
                int queries = config.getInt(prefix + "rate.queries", 1);
                Duration period = Duration.millis((long) (1000 * config.getDouble(prefix + "rate.period", 1))); // rate.period in seconds
                final List listings = config.getList(prefix + "listings");
                initExchange(helperClassName, streamingConfigClassName, queries, period, exchange, listings);
            } else {
                log.warn("Could not find Exchange for property \"xchange." + tag + ".*\"");
            }
        }
    }

    /** You may implement this interface to customize the interaction with the Xchange library for each exchange.
        Set the class name of your Helper in the module configuration using the key:<br/>
        xchange.<marketname>.helper.class=com.foo.bar.MyHelper<br/>
        if you leave out the package name it is assumed to be the same as the XchangeData class (i.e. the xchange
        module package).
     */
    public interface Helper {
        Object[] getTradesParameters(CurrencyPair pair, long lastTradeTime, long lastTradeId);

        Object[] getOrderBookParameters(CurrencyPair pair);

        void handleTrades(Trades tradeSpec);

        void handleOrderBook(OrderBook orderBook);
    }

    private void initExchange(@Nullable String helperClassName, @Nullable String streamingConfigClassName, int queries, Duration per,
            Exchange coinTraderExchange, List listings) {
        com.xeiam.xchange.Exchange xchangeExchange = XchangeUtil.getExchangeForMarket(coinTraderExchange);
        StreamingExchangeService streamingDataService;
        Helper helper = null;
        if (helperClassName != null && !helperClassName.isEmpty()) {
            if (helperClassName.indexOf('.') == -1)
                helperClassName = XchangeData.class.getPackage().getName() + '.' + helperClassName;
            try {
                final Class<?> helperClass = getClass().getClassLoader().loadClass(helperClassName);
                try {
                    helper = (Helper) helperClass.newInstance();
                } catch (InstantiationException | IllegalAccessException e) {
                    log.error("Could not initialize XchangeData because helper class " + helperClassName + " could not be instantiated ", e);
                    return;
                } catch (ClassCastException e) {
                    log.error("Could not initialize XchangeData because helper class " + helperClassName + " does not implement " + Helper.class);
                    return;
                }
            } catch (ClassNotFoundException e) {
                log.error("Could not initialize XchangeData because helper class " + helperClassName + " was not found");
                return;
            }
        }

        ExchangeStreamingConfiguration streamingConfiguration = null;
        if (streamingConfigClassName != null && !streamingConfigClassName.isEmpty()) {
        }
        List<Market> markets = new ArrayList<>(listings.size());
        Market market;
        //  ExchangeStreamingConfiguration streamingConfiguration = new OkCoinExchangeStreamingConfiguration();
        for (Iterator<List> il = listings.iterator(); il.hasNext();) {
            Object listingSymbol = il.next();
            Listing listing = Listing.forSymbol(listingSymbol.toString().toUpperCase());
            market = context.getInjector().getInstance(Market.class).findOrCreate(coinTraderExchange, listing);
            markets.add(market);
        }

        if (streamingConfigClassName != null) {
            RateLimiter rateLimiter = new RateLimiter(queries, per);
            // streamingDataService = xchangeExchange.getStreamingExchangeService(streamingConfiguration);
            for (Iterator<Market> im = markets.iterator(); im.hasNext();) {
                market = im.next();

                ListeningExecutorService service = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
                ListenableFuture<StreamTradesRunnable> streamingTradesFuture = service.submit(new StreamTradesRunnable(context, xchangeExchange, market,
                        rateLimiter, streamingConfigClassName, helper));

                Futures.addCallback(streamingTradesFuture, new FutureCallback<StreamTradesRunnable>() {
                    // we want this handler to run immediately after we push the big red button!
                    @Override
                    public void onSuccess(StreamTradesRunnable streamingTradesFuture) {
                        System.out.println("complete");

                        //walkAwayFrom(explosion);
                    }

                    @Override
                    public void onFailure(Throwable thrown) {
                        System.out.println("failed");
                        //battleArchNemesis(); // escaped the explosion!
                    }
                });

            }
            return;
        } else {
            PollingMarketDataService dataService = xchangeExchange.getPollingMarketDataService();

            RateLimiter rateLimiter = new RateLimiter(queries, per);

            for (Iterator<Market> im = markets.iterator(); im.hasNext(); rateLimiter.execute(new FetchTradesRunnable(context, market, rateLimiter, dataService,
                    helper)))
                market = im.next();

            return;
        }
    }

    private class StreamTradesRunnable implements Callable {

        private final Helper helper;
        DateFormat dateFormat = new SimpleDateFormat("ddMMyy");
        private ExchangeStreamingConfiguration streamingConfiguration;
        private CurrencyPair[] pairs;

        public StreamTradesRunnable(Context context, com.xeiam.xchange.Exchange xchangeExchange, Market market, RateLimiter rateLimiter,
                String streamingConfigClassName, @Nullable Helper helper) {
            this.context = context;
            this.rateLimiter = rateLimiter;
            this.market = market;
            this.helper = helper;
            this.prompt = market.getListing().getPrompt();
            pairs = new CurrencyPair[] { XchangeUtil.getCurrencyPairForListing(market.getListing()) };
            contract = prompt == null ? null : XchangeUtil.getContractForListing(market.getListing());

            //            Thread.currentThread().setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            //                @Override
            //                public void uncaughtException(Thread t, Throwable e) {
            //                    e.printStackTrace();
            //                }
            //            });

            if (streamingConfigClassName.indexOf('.') == -1)
                streamingConfigClassName = XchangeData.class.getPackage().getName() + '.' + streamingConfigClassName;
            try {
                final Class<?> streamingConfigClass = getClass().getClassLoader().loadClass(streamingConfigClassName);
                //  CurrencyPair[] ccy = new CurrencyPair[] { CurrencyPair.BTC_USD };
                try {
                    streamingConfiguration = (ExchangeStreamingConfiguration) CompareUtils.tryToCreateBestMatch(streamingConfigClass, new Object[] { pairs });
                    dataService = xchangeExchange.getStreamingExchangeService(streamingConfiguration);
                    dataService.connect();

                    String str = streamingConfigClass.getCanonicalName();
                } catch (InstantiationException e1) {
                    // TODO Auto-generated catch block
                    log.error("Threw a Execption, full stack trace follows:", e1);

                    e1.printStackTrace();
                } catch (IllegalAccessException e1) {
                    // TODO Auto-generated catch block
                    log.error("Threw a Execption, full stack trace follows:", e1);

                    e1.printStackTrace();
                } catch (InvocationTargetException e1) {
                    // TODO Auto-generated catch block
                    log.error("Threw a Execption, full stack trace follows:", e1);

                    e1.printStackTrace();
                } catch (Exception | Error e2) {
                    // TODO Auto-generated catch block
                    log.error("Threw a Execption, full stack trace follows:", e2);

                    e2.printStackTrace();
                }

                try {
                    streamingConfiguration = (ExchangeStreamingConfiguration) streamingConfigClass.newInstance();

                    //           StreamingExchangeService service = xchangeExchange.getStreamingExchangeService(new (ExchangeStreamingConfiguration) streamingConfigClass(new CurrencyPair[]{ CurrencyPair.BTC_USD }));

                } catch (InstantiationException | IllegalAccessException e) {
                    log.error("Could not initialize XchangeData because stremaing configuration class " + streamingConfigClassName
                            + " could not be instantiated ", e);
                    return;
                } catch (ClassCastException e) {
                    log.error("Could not initialize XchangeData because stremaing configuration class " + streamingConfigClassName + " does not implement "
                            + ExchangeStreamingConfiguration.class);
                    return;
                } catch (Exception | Error e2) {
                    // TODO Auto-generated catch block
                    log.error("Threw a Execption, full stack trace follows:", e2);

                    e2.printStackTrace();
                    return;
                }
            } catch (ClassNotFoundException e) {
                log.error("Could not initialize XchangeData because stremaing configuration class " + streamingConfigClassName + " was not found");
                return;
            } catch (Exception | Error e2) {
                log.error("Threw a Execption, full stack trace follows:", e2);

                // TODO Auto-generated catch block
                e2.printStackTrace();
            }

            Class<? extends ExchangeStreamingConfiguration> myclass = streamingConfiguration.getClass();
            Class<?> streamingConfigClass = null;
            try {
                streamingConfigClass = getClass().getClassLoader().loadClass(streamingConfigClassName);
            } catch (ClassNotFoundException e) {
                // TODO Auto-generated catch block
                log.error("Threw a Execption, full stack trace follows:", e);

                e.printStackTrace();
            } catch (Exception | Error e2) {
                // TODO Auto-generated catch block
                log.error("Threw a Execption, full stack trace follows:", e2);

                e2.printStackTrace();
            }

            try {
                streamingConfiguration = (ExchangeStreamingConfiguration) streamingConfigClass.newInstance();
            } catch (InstantiationException e) {
                // TODO Auto-generated catch block
                log.error("Threw a Execption, full stack trace follows:", e);

                e.printStackTrace();
            } catch (IllegalAccessException e) {
                // TODO Auto-generated catch block
                log.error("Threw a Execption, full stack trace follows:", e);

                e.printStackTrace();
            } catch (Exception | Error e2) {
                log.error("Threw a Execption, full stack trace follows:", e2);

                // TODO Auto-generated catch block
                e2.printStackTrace();
            }
            //   EntityManager entityManager = //.createEntityManager();
            try {

                List<org.cryptocoinpartners.schema.Trade> results = EM.queryList(org.cryptocoinpartners.schema.Trade.class,
                        "select t from Trade t where market=?1 and time=(select max(time) from Trade where market=?1)", market);

                for (org.cryptocoinpartners.schema.Trade trade : results) {
                    // org.cryptocoinpartners.schema.Trade trade = query.getSingleResult();
                    long millis = trade.getTime().getMillis();
                    if (millis > lastTradeTime)
                        lastTradeTime = millis;
                    // todo this is broken and assumes an increasing integer remote key
                    // Long remoteId = Long.valueOf(trade.getRemoteKey().concat(String.valueOf(trade.getTimestamp())));
                    Long remoteId = Long.valueOf(trade.getRemoteKey());
                    if (remoteId > lastTradeId)
                        lastTradeId = remoteId;
                }
            } finally {
                //  EM.em().close();
            }

            //StreamingExchangeService streamingDataService = xchangeExchange.getStreamingExchangeService(streamingConfiguration,new CurrencyPair[]{ CurrencyPair.BTC_USD }));

            lastTradeTime = 0;
            lastTradeId = 0;

        }

        @Override
        public Object call() {
            try {
                while (true) {

                    ExchangeEvent event = dataService.getNextEvent();

                    if (event != null) {
                        //System.out.println("---> " + event.getPayload() + " " + event.getEventType());

                        if (event.getEventType().equals(ExchangeEventType.TRADE)) {
                            com.xeiam.xchange.dto.marketdata.Trade trade = (com.xeiam.xchange.dto.marketdata.Trade) event.getPayload();
                            long remoteId = Long.valueOf(String.valueOf(dateFormat.format(trade.getTimestamp()).concat(trade.getId()))).longValue();
                            if (remoteId > lastTradeId) {

                                Instant tradeInstant = new Instant(trade.getTimestamp());
                                BigDecimal volume = (trade.getType() == OrderType.ASK) ? trade.getTradableAmount().negate() : trade.getTradableAmount();
                                org.cryptocoinpartners.schema.Trade ourTrade = tradeFactory.create(market, tradeInstant, trade.getId(), trade.getPrice(),
                                        volume);

                                context.publish(ourTrade);
                                if (ourTrade.getDao() == null)
                                    System.out.println("empty thingh");
                                lastTradeTime = tradeInstant.getMillis();
                                lastTradeId = remoteId;
                            }

                        } else if (event.getEventType().equals(ExchangeEventType.DEPTH)) {
                            OrderBook orderBook = (OrderBook) event.getPayload();
                            if (helper != null)
                                helper.handleOrderBook(orderBook);
                            Book book = bookFactory.create(new Instant(orderBook.getTimeStamp()), market);

                            // bookBuilder.start(new Instant(orderBook.getTimeStamp()), null, market);
                            LimitOrder limitOrder;
                            for (Iterator<LimitOrder> itb = orderBook.getBids().iterator(); itb.hasNext(); book.addBid(limitOrder.getLimitPrice(),
                                    limitOrder.getTradableAmount()))
                                limitOrder = itb.next();

                            for (Iterator<LimitOrder> ita = orderBook.getAsks().iterator(); ita.hasNext(); book.addAsk(limitOrder.getLimitPrice(),
                                    limitOrder.getTradableAmount()))
                                limitOrder = ita.next();

                            book.build();
                            context.publish(book);

                        }
                        if (event.getEventType().equals(ExchangeEventType.DISCONNECT)) {
                            log.error(this.getClass().getSimpleName() + " Disconnected");
                            //Thread.currentThread().interrupt();
                            //dataService.
                            // dataService.disconnect();
                            //    dataService.connect();
                            //READYSTATE status = dataService.getWebSocketStatus();
                            //  dataService.connect();

                            // let's resubmit and connect
                        }

                    }
                }
            } catch (InterruptedException e) {
                log.error("Threw a Execption, full stack trace follows disconnecting:", e);

                //  Thread.currentThread().interrupt();
                dataService.disconnect();

                // Thread.currentThread().interrupt();

            } catch (RejectedExecutionException rej) {
                log.error("Threw a Execption, full stack trace follows:", rej);

                rej.printStackTrace();
            }

            catch (Exception | Error e2) {
                // TODO Auto-generated catch block
                log.error("Threw a Execption, full stack trace follows:", e2);

                e2.printStackTrace();

            }

            return Thread.currentThread();
        }

        //   private final Book.Builder bookBuilder = new Book.Builder();
        private final boolean getTradesNext = true;
        private StreamingExchangeService dataService = null;
        private final Context context;
        private final Market market;
        private final RateLimiter rateLimiter;
        private final FuturesContract contract;
        private long lastTradeTime;
        private final Prompt prompt;
        private long lastTradeId;
    }

    private class FetchTradesRunnable implements Runnable {

        private final Helper helper;
        DateFormat dateFormat = new SimpleDateFormat("ddMMyy");

        public FetchTradesRunnable(Context context, Market market, RateLimiter rateLimiter, PollingMarketDataService dataService, @Nullable Helper helper) {
            this.context = context;
            this.market = market;
            this.rateLimiter = rateLimiter;
            this.dataService = dataService;
            this.helper = helper;
            this.prompt = market.getListing().getPrompt();
            pair = XchangeUtil.getCurrencyPairForListing(market.getListing());
            contract = prompt == null ? null : XchangeUtil.getContractForListing(market.getListing());

            lastTradeTime = 0;
            lastTradeId = 0;
            // EntityManager entityManager = PersistUtil.createEntityManager();
            try {

                List<org.cryptocoinpartners.schema.Trade> results = EM.queryList(org.cryptocoinpartners.schema.Trade.class,
                        "select t from Trade t where market=?1 and time=(select max(time) from Trade where market=?1)", market);

                for (org.cryptocoinpartners.schema.Trade trade : results) {
                    // org.cryptocoinpartners.schema.Trade trade = query.getSingleResult();
                    long millis = trade.getTime().getMillis();
                    if (millis > lastTradeTime)
                        lastTradeTime = millis;
                    // todo this is broken and assumes an increasing integer remote key
                    // Long remoteId = Long.valueOf(trade.getRemoteKey().concat(String.valueOf(trade.getTimestamp())));
                    Long remoteId = Long.valueOf(trade.getRemoteKey());
                    if (remoteId > lastTradeId)
                        lastTradeId = remoteId;
                }
            } finally {
                // EM.em().close();
            }
        }

        @Override
        public void run() {
            try {
                if (getTradesNext)
                    getTrades();
                else
                    getBook();
            } finally {
                getTradesNext = !getTradesNext;
                rateLimiter.execute(this); // run again. requeue
            }
        }

        protected void getTrades() {
            try {
                Object params[];
                if (helper != null)
                    params = helper.getTradesParameters(pair, lastTradeTime, lastTradeId);
                else {
                    if (contract == null)
                        params = new Object[] {};
                    else
                        params = new Object[] { contract };

                }

                Trades tradeSpec = dataService.getTrades(pair, params);
                if (helper != null)
                    helper.handleTrades(tradeSpec);
                List trades = tradeSpec.getTrades();
                Iterator<Trade> ilt = trades.iterator();
                do {
                    if (!ilt.hasNext())
                        break;
                    com.xeiam.xchange.dto.marketdata.Trade trade = ilt.next();
                    long remoteId = Long.valueOf(String.valueOf(dateFormat.format(trade.getTimestamp()).concat(trade.getId()))).longValue();
                    if (remoteId > lastTradeId) {
                        Instant tradeInstant = new Instant(trade.getTimestamp());
                        BigDecimal volume = (trade.getType() == OrderType.ASK) ? trade.getTradableAmount().negate() : trade.getTradableAmount();

                        org.cryptocoinpartners.schema.Trade ourTrade = tradeFactory.create(market, tradeInstant, trade.getId(), trade.getPrice(), volume);
                        context.publish(ourTrade);
                        if (ourTrade.getDao() == null)
                            System.out.println("duffer");
                        lastTradeTime = tradeInstant.getMillis();
                        lastTradeId = remoteId;
                    }

                } while (true);
            } catch (IOException e) {
                log.warn("Could not get trades for " + market, e);
                context.publish(new MarketDataError(market, e));
            }
            return;
        }

        protected void getBook() {
            try {
                Object params[];
                if (helper != null)
                    params = helper.getOrderBookParameters(pair);
                else {
                    if (contract == null)
                        params = new Object[] {};
                    else
                        params = new Object[] { contract };

                }

                OrderBook orderBook = dataService.getOrderBook(pair, params);
                if (helper != null)
                    helper.handleOrderBook(orderBook);
                Book book = bookFactory.create(new Instant(orderBook.getTimeStamp()), market);
                LimitOrder limitOrder;
                for (Iterator<LimitOrder> itb = orderBook.getBids().iterator(); itb.hasNext(); book.addBid(limitOrder.getLimitPrice(),
                        limitOrder.getTradableAmount()))
                    limitOrder = itb.next();

                for (Iterator<LimitOrder> ita = orderBook.getAsks().iterator(); ita.hasNext(); book.addAsk(limitOrder.getLimitPrice(),
                        limitOrder.getTradableAmount()))
                    limitOrder = ita.next();

                book.build();
                //        log.debug("publish book:" + book.getId());
                context.publish(book);

            } catch (IOException e) {
                log.warn("Could not get book for " + market, e);
                context.publish(new MarketDataError(market, e));
            }
        }

        // private final Book.Builder bookBuilder = new Book.Builder();
        private boolean getTradesNext = true;
        private final PollingMarketDataService dataService;
        private final RateLimiter rateLimiter;
        private final Context context;
        private final Market market;
        private final CurrencyPair pair;
        private final FuturesContract contract;
        private long lastTradeTime;
        private final Prompt prompt;
        private long lastTradeId;
    }

    protected static Logger log = LoggerFactory.getLogger("org.cryptocoinpartners.xchangeData");
    private final BookFactory bookFactory;

    private final TradeFactory tradeFactory;
    //  @Inject
    //protected EntityManager entityManager;

    private final Context context;
}
