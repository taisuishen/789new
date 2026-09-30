package com.bingo789.common.obs;

/**
 * An uploaded object.
 *
 * @param key  object key inside the bucket: the durable reference to store in databases
 * @param url  address a client can open now: the public URL, or a signed URL valid for bingo.obs.signed-url-ttl
 *             when the bucket is private (never store signed URLs, they expire)
 */
public record StoredObject(String key, String url, String contentType, long size) {
}
