package com.ubers.observador.service

import com.ubers.observador.domain.lifecycle.OfferCardParser
import com.ubers.observador.domain.models.UberOffer

/** Android-facing name retained for callers; all parsing is pure and shared with close verification. */
object OfferParser {
    fun parseAll(texts: List<String>): List<UberOffer> = OfferCardParser.parseAll(texts)
    fun parse(texts: List<String>): UberOffer? = OfferCardParser.parse(texts)
}
