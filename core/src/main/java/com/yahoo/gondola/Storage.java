/*
 * Copyright 2015, Yahoo Inc.
 * Copyrights licensed under the New BSD License.
 * See the accompanying LICENSE file for terms.
 */

package com.yahoo.gondola;

/**
 * The implementation of this interface must be thread-safe.
 * TODO: add a batch method to get log entries, to improve performance
 * TODO: add a batch method to append a set of entries, to improve performance
 */
public interface Storage extends Stoppable {
    /**
     * Start any threads needed by the implementaiton.
     */
    public void start();

    /**
     * Stops all threads in the object. After this call, it should be possible to call start() to restart
     * the threads.
     */
    public void stop();

    /**
     * Returns true if calling methods in this class is likely to succeed.
     * Returns false if calling methods in this class is likely to fail.
     */
    public boolean isOperational();

    /**
     * An address is an arbitrary string that contains enough information to locate a member.
     * Before a member uses the storage, it should first read the address and if not null, attempt to contact the
     * member using the address. If the contacted member is still alive, the current member should not
     * use the storage.
     *
     * @return A possibly-null location string.
     */
    public String getAddress(int memberId) throws Exception;

    /**
     * See Network.getAddress(). Saves an address. When a process no longer needs to use the storage, it can
     * set the address to null.
     * @param address A possibly-null address string.
     */
    public void setAddress(int memberId, String address) throws Exception;

    /**
     * If save() was never called, returns 1.
     */
    public int getCurrentTerm(int memberId) throws Exception;

    /**
     * If save() was never called, returns -1.
     */
    public int getVotedFor(int memberId) throws Exception;

    /**
     * When a member votes for another member, it should call this method to record the vote.
     *
     * @param pid The process id of the current process.
     */
    public void saveVote(int memberId, int currentTerm, int votedFor) throws Exception;

    /**
     * Since appendLogEntry() can save entries out of order, it's possible to end up with gaps near the end of the log.
     * maxGap is the largest gap that can occur from the last written entry. 
     * Returns the largest allowed gap from the last written entry. A value of 0 means that there are no
     * gaps from the last written entry.
     *
     * @return If save() was never called, returns 0.
     */
    public int getMaxGap(int memberId) throws Exception;

    /**
     * Sets the largest allowed gap from the last written entry. This value should never be lower than the last
     * saved value, unless it's being set to 0.
     *
     * @param memberId
     * @param maxGap
     * @param pid The process id of the current process.
     */
    public void setMaxGap(int memberId, int maxGap) throws Exception;

    /**
     * Returns the currently saved pid. Used to detect if another instance is running.
     *
     * @return The current saved pid or null if there is none.
     */
    public String getPid(int memberId) throws Exception;

    /**
     *
     * @param memberId
     * @param pid The process id of the current process.
     */
    public void setPid(int memberId, String pid) throws Exception;

    /**
     * Returns the number of all log entries for the specified member.
     *
     * @param index The index of the log entry to delete.
     */
    public int count(int memberId) throws Exception;

    /**
     * Returns the log entry at the specified index.
     * The caller should call LogEntry.release() when it no longer needs the log entry.
     * 
     * @param index must be >= 1
     * @return null if index does not exist.
     */
    public LogEntry getLogEntry(int memberId, int index) throws Exception;

    /**
     * Returns true if a log entry with the specified term and index exist for the specified member.
     *
     * @param memberId The id of the member.
     * @param index must be >= 1
     */
    public boolean hasLogEntry(int memberId, int term, int index) throws Exception;

    /**
     * Returns the saved log entry with the highest index for the specified member. 
     *
     * @param memberId The id of the member.
     * @return null if no entries have been written to the log.
     * @throws Exception
     */
    public LogEntry getLastLogEntry(int memberId) throws Exception;

    /**
     * The bufferLen bytes will be written from the buffer starting at bufferOffset.
     * This method blocks until the entry has been written. The caller must not attempt to
     * append an index that could create a gap greater than maxGap.
     * <p>
     * Note: This method can be called with indices not in order.
     * This feature is used to gain performance with concurrency.
     * <p>
     * Implementation note: The operation should fail if the storage already has an entry
     * with the specified index. The code that uses the Storage class deletes entries
     * before writing them and so there should not be any conflicts.
     *
     * @param index must be >= 1
     */
    public void appendLogEntry(int memberId, int term, int index,
                               byte[] buffer, int bufferOffset, int bufferLen) throws Exception;

    /**
     * Deletes the entry at the specified index.
     * An exception should be thrown if the index does not exist.
     *
     * @param index The index of the log entry to delete.
     * @param memberId The id of the member.
     */
    public void delete(int memberId, int index) throws Exception;

    /**
     * Reclaims the entry object for reuse.
     *
     * @param entry A non-null entry object that is no longer used.
     */
    public void checkin(LogEntry entry);
}
