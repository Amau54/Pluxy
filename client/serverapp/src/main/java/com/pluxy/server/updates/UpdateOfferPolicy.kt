package com.pluxy.server.updates

/** A dismissed offer must not hide an update on the next app session. */
internal class UpdateOfferPolicy {
    private var offered = false
    fun beginSession() { offered = false }
    fun mayOffer(now: Long, offerAfter: Long): Boolean = !offered || now >= offerAfter
    fun markOffered() { offered = true }
}
