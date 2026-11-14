package lk.xfare.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FareParserUnitTest {

    @Test
    fun extractsSriLankanRsAndLkrFaresAndIgnoresWalletAndPerKmRates() {
        val samplePickMe = listOf(
            "Wallet Balance Rs. 4,500",
            "Pickup 1.2 km",
            "Trip 4.5 km",
            "Rs. 1,450.00",
            "Rs. 120/km"
        )
        val fare = FareParser.extractFare(samplePickMe)
        assertNotNull(fare)
        assertEquals(1450.0, fare!!, 0.01)

        val sampleUber = listOf(
            "LKR 980",
            "1.2 km away",
            "4.5 km (14 min) trip"
        )
        val uberFare = FareParser.extractFare(sampleUber)
        assertNotNull(uberFare)
        assertEquals(980.0, uberFare!!, 0.01)
    }

    @Test
    fun extractsStandaloneFareWhenPrefixIsOmittedAndIgnoresUnderFifty() {
        val standaloneCard = listOf(
            "4.95 ★",
            "35",
            "1,280",
            "1.2 km",
            "4.5 km",
            "Accept"
        )
        val fare = FareParser.extractFare(standaloneCard)
        assertNotNull(fare)
        assertEquals(1280.0, fare!!, 0.01)
    }

    @Test
    fun sumsUpToTwoKmDistancesAndHandlesMixedCase() {
        val nodes = listOf(
            "1.2 km",
            "4.5 KM",
            "9.0 Km",
            "LKR 1,200.00"
        )
        val totalKm = FareParser.extractTotalDistanceKm(nodes)
        assertNotNull(totalKm)
        assertEquals(5.7, totalKm!!, 0.001)
    }

    @Test
    fun detectsRideScreensByPackageOrKeywords() {
        assertTrue(FareParser.isTargetRidePackage("com.pickme.driver"))
        assertTrue(FareParser.isTargetRidePackage("com.ubercab.driver"))
        assertTrue(FareParser.isTargetRidePackage("lk.helago.driver"))
        assertFalse(FareParser.isTargetRidePackage("lk.xfare.app"))
        assertFalse(FareParser.isTargetRidePackage("com.android.systemui"))

        assertTrue(
            FareParser.isRideScreenByKeywords(
                listOf("Trip Radar", "Pickup 1.2 km", "Drop 4.5 km", "LKR 1,100", "Accept")
            )
        )
        assertFalse(
            FareParser.isRideScreenByKeywords(
                listOf("Wallet Balance", "Rs. 2,500", "Settings")
            )
        )
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
