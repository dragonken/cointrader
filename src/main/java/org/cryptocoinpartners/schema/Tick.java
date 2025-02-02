package org.cryptocoinpartners.schema;

import javax.annotation.Nullable;
import javax.persistence.Entity;
import javax.persistence.ManyToOne;
import javax.persistence.Transient;

import org.joda.time.Instant;

/**
 * A Tick is a point-in-time snapshot of a Market's last price, volume and most recent Book
 *
 * @author Tim Olson
 */
@Entity
public class Tick extends PriceData implements Spread {

    public Instant getStartInstant() {
        return startInstant;
    }

    @Transient
    public Instant getEndInstant() {
        return getTime();
    }

    @ManyToOne
    public Book getLastBook() {
        return lastBook;
    }

    /** @return null if no book was found prior to the window */
    @Override
    @Transient
    public @Nullable
    Offer getBestBid() {
        return lastBook == null ? null : lastBook.getBestBid();
    }

    /** @return null if no book was found prior to the window */
    @Override
    @Transient
    public @Nullable
    Offer getBestAsk() {
        return lastBook == null ? null : lastBook.getBestAsk();
    }

    public Tick(Market market, Instant startInstant, Instant endInstant, @Nullable Long lastPriceCount, @Nullable Long volumeCount, Book lastBook) {
        super(endInstant, null, market, lastPriceCount, volumeCount);
        this.startInstant = startInstant;
        this.lastBook = lastBook;
    }

    @Override
    public String toString() {
        return String.format("Tick{%s last:%g@%g bid:%s ask:%s}", getMarket(), getVolumeAsDouble(), getPriceAsDouble(), getBestBid(), getBestAsk());
    }

    // JPA
    protected Tick() {
    }

    protected void setStartInstant(Instant startInstant) {
        this.startInstant = startInstant;
    }

    protected void setLastBook(Book lastBook) {
        this.lastBook = lastBook;
    }

    private Instant startInstant;
    private Book lastBook;

    @Override
    public Offer getBestBidByVolume(DiscreteAmount volume) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Offer getBestAskByVolume(DiscreteAmount volume) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void persit() {
        // TODO Auto-generated method stub

    }

    @Override
    public void detach() {
        // TODO Auto-generated method stub

    }

    @Override
    public void merge() {
        // TODO Auto-generated method stub

    }
}
