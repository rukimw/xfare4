package lk.xfare.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FareParserUnitTest {

    @Test
    fun extractsSriLankanRsAndLkrFaresAndIgnoresPerKmRates() {
        val samplePickMe = listOf(
            "Pickup 1.2 km",
            "Trip 7.8 km",
            "Rs. 1,450.00",
            "Rs. 120/km"
        )
        val fare = FareParser.extractFare(samplePickMe)
        assertNotNull(fare)
        assertEquals(1450.0, fare!!, 0.01)

        val sampleUber = listOf(
            "LKR 980",
            "850 m away",
            "6.4 km (14 min) trip"
        )
        val uberFare = FareParser.extractFare(sampleUber)
        assertNotNull(uberFare)
        assertEquals(980.0, uberFare!!, 0.01)
    }

    @Test
    fun sumsPickupAndTripDistancesIncludingMeters() {
        val nodes = listOf(
            "850 m away",
            "6.4 km (14 mins)",
            "LKR 1,200.00"
        )
        val totalKm = FareParser.extractTotalDistanceKm(nodes)
        assertNotNull(totalKm)
        assertEquals(7.25, totalKm!!, 0.001)
    }

    @Test
    fun calculatesNetFareAndRatePerKmCorrectly() {
        val calc = FareParser.calculate(
            grossFare = 2000.0,
            totalDistanceKm = 10.0,
            commissionPercent = 15.0
        )
        assertNotNull(calc)
        assertEquals(1700.0, calc!!.netFare, 0.001)
        assertEquals(170.0, calc.netRatePerKm, 0.001)

        assertNull(FareParser.calculate(1000.0, 0.0, 15.0))
    }
}
