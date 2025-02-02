package org.cryptocoinpartners.schema;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import javax.persistence.Basic;
import javax.persistence.Cacheable;
import javax.persistence.Entity;
import javax.persistence.Index;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.NamedQuery;
import javax.persistence.NoResultException;
import javax.persistence.PostPersist;
import javax.persistence.QueryHint;
import javax.persistence.Table;
import javax.persistence.Transient;

import org.cryptocoinpartners.enumeration.FeeMethod;
import org.cryptocoinpartners.schema.dao.MarketDao;
import org.cryptocoinpartners.util.EM;
import org.cryptocoinpartners.util.RemainderHandler;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.AssistedInject;

/**
 * Represents the possibility to trade one Asset for another at a specific Exchange.
 *
 * @author Tim Olson
 */
@Entity
@Cacheable
@NamedQuery(name = "Market.findByMarket", query = "select m from Market m where exchange=?1 and listing=?2", hints = { @QueryHint(name = "org.hibernate.cacheable", value = "true") })
@Table(indexes = { @Index(columnList = "exchange"), @Index(columnList = "listing"), @Index(columnList = "active") })
public class Market extends EntityBase {
    @Inject
    protected MarketDao marketDao;

    @Inject
    protected transient static MarketFactory marketFactory;

    public static List<Market> findAll() {
        return EM.queryList(Market.class, "select m from Market m");
    }

    /** adds the Market to the database if it does not already exist */
    public static Market findOrCreate(Exchange exchange, Listing listing) {
        return findOrCreate(exchange, listing, listing.getPriceBasis(), listing.getVolumeBasis());
    }

    @PostPersist
    private void postPersist() {

        //PersistUtil.detach(this);

    }

    public static Market findOrCreate(Exchange exchange, Listing listing, double quoteBasis, double volumeBasis) {
        // final String queryStr = "select m from Market m where exchange=?1 and listing=?2";
        try {
            return EM.namedQueryOne(Market.class, "Market.findByMarket", exchange, listing);
        } catch (NoResultException e) {

            Market ml = marketFactory.create(exchange, listing, quoteBasis, volumeBasis);
            //  Market ml = new Market(exchange, listing, quoteBasis, volumeBasis);
            ml.persit();
            // marketDao.persist(ml);
            return ml;
        }
    }

    /**
     @return active Markets for the given exchange
     */
    public static List<Market> find(Exchange exchange) {
        return EM.queryList(Market.class, "select s from Market s where exchange=?1 and active=?2", exchange, true);
    }

    /**
     @return active Markets for the given listing
     */
    public static List<Market> find(Listing listing) {
        return EM.queryList(Market.class, "select s from Market s where listing=?1 and active=?2", listing, true);
    }

    @ManyToOne(optional = false)
    public Exchange getExchange() {
        return exchange;
    }

    @ManyToOne(optional = false)
    //, cascade = CascadeType.ALL)
    @JoinColumn(name = "listing")
    public Listing getListing() {
        return listing;
    }

    @Basic(optional = false)
    public double getPriceBasis() {

        return listing.getPriceBasis() == 0 ? priceBasis : listing.getPriceBasis();

    }

    @Transient
    public int getScale() {

        int length = (int) (Math.log10(getPriceBasis()));
        return length;
    }

    @Basic(optional = false)
    public double getVolumeBasis() {
        return listing.getVolumeBasis() == 0 ? volumeBasis : listing.getVolumeBasis();

    }

    /** @return true iff the Listing is currently traded at the Exchange.  The Market could have been retired. */
    public boolean isActive() {
        return active;
    }

    @Transient
    public Asset getBase() {
        return listing.getBase();
    }

    @Transient
    public Asset getQuote() {
        return listing.getQuote();
    }

    @Transient
    public int getMargin() {
        return listing.getMargin() == 0 ? exchange.getMargin() : listing.getMargin();

    }

    @Transient
    public double getFeeRate() {
        return listing.getFeeRate() == 0 ? exchange.getFeeRate() : listing.getFeeRate();

    }

    @Transient
    public FeeMethod getMarginFeeMethod() {
        return listing.getMarginFeeMethod() == null ? exchange.getMarginFeeMethod() : listing.getMarginFeeMethod();

    }

    @Transient
    public FeeMethod getFeeMethod() {
        return listing.getFeeMethod() == null ? exchange.getFeeMethod() : listing.getFeeMethod();

    }

    @Transient
    public double getMultiplier() {
        return listing.getMultiplier();

    }

    @Transient
    public double getTickValue() {
        return listing.getTickValue();

    }

    @Transient
    public double getContractSize() {
        return listing.getContractSize();

    }

    @Transient
    public double getTickSize() {
        return listing.getTickSize();

    }

    @Transient
    public Asset getTradedCurrency() {
        return listing.getTradedCurrency();

    }

    @Transient
    public String getSymbol() {
        return exchange.toString() + ':' + listing.toString();
    }

    @Override
    public String toString() {
        return getSymbol();
    }

    public static Market forSymbol(String marketSymbol) {

        for (Market market : findAll()) {
            if (market.getSymbol().equalsIgnoreCase(marketSymbol))
                return market;
        }
        return null;
    }

    public static List<String> allSymbols() {
        List<String> result = new ArrayList<>();
        List<Market> markets = EM.queryList(Market.class, "select m from Market m");
        for (Market market : markets)
            result.add((market.getSymbol()));
        return result;
    }

    public static class MarketAmountBuilder {

        public DiscreteAmount fromPriceCount(long count) {
            return priceBuilder.fromCount(count);
        }

        public DiscreteAmount fromVolumeCount(long count) {
            return volumeBuilder.fromCount(count);
        }

        public DiscreteAmount fromPrice(BigDecimal amount, RemainderHandler remainderHandler) {
            return priceBuilder.fromValue(amount, remainderHandler);
        }

        public DiscreteAmount fromVolume(BigDecimal amount, RemainderHandler remainderHandler) {
            return volumeBuilder.fromValue(amount, remainderHandler);
        }

        public MarketAmountBuilder(double priceBasis, double volumeBasis) {
            this.priceBuilder = DiscreteAmount.withBasis(priceBasis);
            this.volumeBuilder = DiscreteAmount.withBasis(volumeBasis);
        }

        private final DiscreteAmount.DiscreteAmountBuilder priceBuilder;
        private final DiscreteAmount.DiscreteAmountBuilder volumeBuilder;
    }

    public MarketAmountBuilder buildAmount() {
        if (marketAmountBuilder == null)
            marketAmountBuilder = new MarketAmountBuilder(getPriceBasis(), getVolumeBasis());
        return marketAmountBuilder;
    }

    // JPA
    protected Market() {
    }

    protected void setExchange(Exchange exchange) {
        this.exchange = exchange;
    }

    protected void setListing(Listing listing) {
        this.listing = listing;
    }

    protected void setActive(boolean active) {
        this.active = active;
    }

    protected void setPriceBasis(double quoteBasis) {
        this.priceBasis = quoteBasis;
    }

    protected void setVolumeBasis(double volumeBasis) {
        this.volumeBasis = volumeBasis;
    }

    @AssistedInject
    private Market(@Assisted Exchange exchange, @Assisted Listing listing, @Assisted("marketPriceBasis") double priceBasis,
            @Assisted("marketVolumeBasis") double volumeBasis) {
        this.exchange = exchange;
        this.listing = listing;
        this.priceBasis = priceBasis;
        this.volumeBasis = volumeBasis;
        this.active = true;
    }

    private Exchange exchange;
    private Listing listing;
    private double priceBasis;
    private double volumeBasis;
    private boolean active;
    private MarketAmountBuilder marketAmountBuilder;

    @Override
    public void persit() {
        if (listing != null)
            if (listing.find() == null)
                listing.persit();
        marketDao.persist(this);
    }

    @Override
    public void detach() {
        marketDao.detach(this);

    }

    @Override
    public void merge() {
        marketDao.merge(this);

    }
}
