package com.bingo789.kyc.web.dto;

/**
 * @param url address to show the image and to send back in the submission; a signed URL that expires when the
 *            bucket is private (upload again if it did)
 * @param key the object key (may be sent instead of the URL)
 */
public record UploadedImageView(String url, String key) {
}
