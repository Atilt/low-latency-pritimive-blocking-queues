package uk.co.boundedbuffer;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Copyright 2014 Rob Austin
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * <a href="http://www.apache.org/licenses/LICENSE-2.0">...</a>
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @author Rob Austin, Atilt
 * @since 1.1
 */
class AbstractBlockingQueue {

    private static final VarHandle READ_LOCATION_OFFSET;
    private static final VarHandle WRITE_LOCATION_OFFSET;

    static {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        try {
            READ_LOCATION_OFFSET = lookup.findVarHandle(AbstractBlockingQueue.class, "readLocation", int.class);
            WRITE_LOCATION_OFFSET = lookup.findVarHandle(AbstractBlockingQueue.class, "writeLocation", int.class);
        } catch (NoSuchFieldException | IllegalAccessException exception) {
            throw new AssertionError(exception);
        }
    }

    final int capacity;

    // only set and read by the producer thread, ( that the thread that's calling put(), offer() or add() )
    int producerWriteLocation;

    // only set and read by the consumer thread, ( that the thread that's calling get(), poll() or peek() )
    int consumerReadLocation;

    // we set volatiles here, for the writes we use putOrderedInt ( as this is quicker ),
    // but for the read of a volatile there is no performance benefit un using getOrderedInt.
    volatile int readLocation;
    volatile int writeLocation;

    /**
     * @param capacity Creates an BlockingQueue with the given (fixed) capacity
     */
    public AbstractBlockingQueue(int capacity) {
        if (capacity == 0) {
            throw new IllegalArgumentException();
        }
        this.capacity = capacity + 1;
    }


    /**
     * Creates an BlockingQueue with the default capacity of 1024
     */
    public AbstractBlockingQueue() {
        this.capacity = 1024;
    }


    void setWriteLocation(final int nextWriteLocation) {


        // putOrderedInt wont immediately make the updates available, even on this thread, so will update the field so the change is immediately visible to, at least this thread. ( note the field is non-volatile )
        this.producerWriteLocation = nextWriteLocation;

        // the line below, is where the write memory barrier occurs,
        // we have just written back the data in the line above ( which is not require to have a memory barrier as we will be doing that in the line below

        //Pre Java 9
        //unsafe.putOrderedInt(this, WRITE_LOCATION_OFFSET, nextWriteLocation);

        // write back the next write location
        WRITE_LOCATION_OFFSET.setRelease(this, nextWriteLocation);
    }

    void setReadLocation(final int nextReadLocation) {

        // putOrderedInt won't immediately make the updates available, even on this thread, so will update the field so the change is immediately visible to, at least this thread. ( note the field is non-volatile )
        this.consumerReadLocation = nextReadLocation;

        // the write memory barrier will occur here, as we are storing the nextReadLocation
        READ_LOCATION_OFFSET.setRelease(this, nextReadLocation);
        //unsafe.putOrderedInt(this, READ_LOCATION_OFFSET, nextReadLocation);
    }

    /**
     * currently implement as a spin lock
     */
    private void blockAtTake() {
    }

    /**
     * currently implement as a spin lock
     *
     * @param timeoutAt returns false if the timeoutAt time is reached
     */
    private boolean blockAtTake(long timeoutAt) {
        return timeoutAt > System.nanoTime();
    }

    /**
     * currently implement as a spin lock
     */
    private void blockAtAdd() {
    }

    /**
     * currently implement as a spin lock
     *
     * @param timeoutAt returns false if the timeoutAt time is reached
     */
    boolean timeAvailable(long timeoutAt) {
        return timeoutAt <= System.nanoTime();
    }


    /**
     * This method is not thread safe it therefore only provides and approximation of the size,
     * the size will be corrected if nothing was added or removed from the queue at the time it was called
     *
     * @return an approximation of the size
     */
    public int size() {
        int size = this.readLocation - this.writeLocation;
        if (size < 0) {
            size += capacity;
        }
        return size;
    }


    /**
     * The items will be cleared correctly only if nothing was added or removed from the queue at the time it was called
     *
     * @return an approximation of the size
     */
    public void clear() {
        readLocation = writeLocation;
    }


    /**
     * This method does not lock, it therefore only provides and approximation of isEmpty(),
     * it will be correct, if nothing was added or removed from the queue at the time it was called.
     *
     * @return an approximation of isEmpty()
     */
    public boolean isEmpty() {
        return readLocation == writeLocation;
    }

    /**
     * @param writeLocation the current write location
     * @return the next write location
     */
    int getNextWriteLocationThrowIfFull(int writeLocation) throws IllegalStateException {

        // we want to minimize the number of volatile reads, so we read the writeLocation just once.

        // sets the nextWriteLocation my moving it on by 1, this may cause it to wrap back to the start.
        final int nextWriteLocation = (writeLocation + 1) % capacity;

        if (nextWriteLocation == capacity) {

            if (readLocation == 0)
                throw new IllegalStateException("queue is full");

        } else if (nextWriteLocation == readLocation)
            // this condition handles the case general case where the read is at the start of the backing array and we are at the end,
            // blocks as our backing array is full, we will wait for a read, ( which will cause a change on the read location )
            throw new IllegalStateException("queue is full");

        return nextWriteLocation;
    }


    /**
     * @param writeLocation the current write location
     * @return the next write location
     */
    int blockForWriteSpaceInterruptibly(int writeLocation) throws InterruptedException {

        // we want to minimize the number of volatile reads, so we read the writeLocation just once.

        // sets the nextWriteLocation my moving it on by 1, this may cause it to wrap back to the start.
        final int nextWriteLocation = (writeLocation + 1) % capacity;

        if (nextWriteLocation == capacity)

            while (readLocation == 0) {

                if (Thread.interrupted())
                    throw new InterruptedException();


                // // this condition handles the case where writer has caught up with the read,
                // we will wait for a read, ( which will cause a change on the read location )
                blockAtAdd();

            }
        else


            while (nextWriteLocation == readLocation) {

                if (Thread.interrupted())
                    throw new InterruptedException();

                // this condition handles the case general case where the read is at the start of the backing array and we are at the end,
                // blocks as our backing array is full, we will wait for a read, ( which will cause a change on the read location )
                blockAtAdd();

            }
        return nextWriteLocation;
    }

    /**
     * @param writeLocation the current write location
     * @return the next write location
     */
    int blockForWriteSpace(int writeLocation) {

        // we want to minimize the number of volatile reads, so we read the writeLocation just once.

        // sets the nextWriteLocation my moving it on by 1, this may cause it to wrap back to the start.
        final int nextWriteLocation = (writeLocation + 1) % capacity;

        if (nextWriteLocation == this.capacity) {

            while (this.readLocation == 0) {
                // // this condition handles the case where writer has caught up with the read,
                // we will wait for a read, ( which will cause a change on the read location )
                blockAtAdd();
            }
        } else {
            while (nextWriteLocation == readLocation) {
                // this condition handles the case general case where the read is at the start of the backing array and we are at the end,
                // blocks as our backing array is full, we will wait for a read, ( which will cause a change on the read location )
                blockAtAdd();
            }
        }

        return nextWriteLocation;
    }

    /**
     * @param timeout      how long to wait before giving up, in units of
     *                     <tt>unit</tt>
     * @param unit         a <tt>TimeUnit</tt> determining how to interpret the
     *                     <tt>timeout</tt> parameter
     * @param readLocation we want to minimize the number of volatile reads, so we read the readLocation just once and get it passed in
     * @return
     * @throws TimeoutException
     */
    int blockForReadSpace(long timeout, TimeUnit unit, int readLocation) throws TimeoutException {

        // sets the nextReadLocation my moving it on by 1, this may cause it to wrap back to the start.
        final int nextReadLocation = (readLocation + 1) % capacity;

        final long timeoutAt = System.nanoTime() + unit.toNanos(timeout);

        // in the for loop below, we are blocked reading unit another item is written, this is because we are empty ( aka size()=0)
        // inside the for loop, getting the 'writeLocation', this will serve as our read memory barrier.

        while (this.writeLocation == readLocation) {
            if (!blockAtTake(timeoutAt)) {
                throw new TimeoutException();
            }
        }

        return nextReadLocation;
    }


    /**
     * /**
     *
     * @param readLocation we want to minimize the number of volatile reads, so we read the readLocation just once, and pass it in
     * @return
     */
    int blockForReadSpace(int readLocation) {

        // sets the nextReadLocation my moving it on by 1, this may cause it to wrap back to the start.
        final int nextReadLocation = (readLocation + 1) % this.capacity;

        // in the for loop below, we are blocked reading unit another item is written, this is because we are empty ( aka size()=0)
        // inside the for loop, getting the 'writeLocation', this will serve as our read memory barrier.
        while (this.writeLocation == readLocation) {
            blockAtTake();
        }

        return nextReadLocation;
    }


    /**
     * /**
     *
     * @param readLocation we want to minimize the number of volatile reads, so we read the readLocation just once, and pass it in
     * @return
     */
    int blockForReadSpaceThrowNoSuchElementException(int readLocation) {

        // sets the nextReadLocation my moving it on by 1, this may cause it to wrap back to the start.
        final int nextReadLocation = (readLocation + 1) % capacity;

        // in the for loop below, we are blocked reading unit another item is written, this is because we are empty ( aka size()=0)
        // inside the for loop, getting the 'writeLocation', this will serve as our read memory barrier.
        while (writeLocation == readLocation) {
            throw new NoSuchElementException();
        }

        return nextReadLocation;
    }


    /**
     * Returns the number of additional elements that this queue can ideally
     * (in the absence of memory or resource constraints) accept without
     * blocking, or <tt>Integer.MAX_VALUE</tt> if there is no intrinsic
     * limit.
     * <p>
     * <p>Note that you <em>cannot</em> always tell if an attempt to insert
     * an element will succeed by inspecting <tt>remainingCapacity</tt>
     * because it may be the case that another thread is about to
     * insert or remove an element.
     *
     * @return the remaining capacity
     */
    public int remainingCapacity() {

        int readLocation = this.readLocation;
        int writeLocation = this.writeLocation;

        if (writeLocation < readLocation)
            writeLocation += capacity;


        return (capacity - 1) - (writeLocation - readLocation);
    }
}
