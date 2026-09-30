package com.bingo789.gateway.admission;

/**
 * Body data of the 429 answer at capacity.
 *
 * @param ticket            opaque signed ticket; poll {@code GET /api/queue/status?ticket=<ticket>}
 * @param position          players ahead (ticket number - admitted)
 * @param retryAfterSeconds when to poll next (also sent as Retry-After)
 */
public record QueueTicketView(String ticket, long position, int retryAfterSeconds) {
}
