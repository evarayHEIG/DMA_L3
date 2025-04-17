package ch.heigvd.iict.dma.wifirtt

import android.net.wifi.rtt.RangingResult
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.map
import ch.heigvd.iict.dma.wifirtt.config.MapConfig
import ch.heigvd.iict.dma.wifirtt.config.MapConfigs.b30
import ch.heigvd.iict.dma.wifirtt.config.MapConfigs.levelB
import ch.heigvd.iict.dma.wifirtt.models.RangedAccessPoint
import com.lemmingapex.trilateration.NonLinearLeastSquaresSolver
import com.lemmingapex.trilateration.TrilaterationFunction
import org.apache.commons.math3.fitting.leastsquares.LevenbergMarquardtOptimizer


class WifiRttViewModel : ViewModel() {

    // PERMISSIONS MANAGEMENT
    private val _wifiRttPermissionsGranted = MutableLiveData<Boolean>(null)
    val wifiRttPermissionsGranted : LiveData<Boolean> get() = _wifiRttPermissionsGranted

    fun wifiRttPermissionsGrantedUpdate(granted : Boolean) {
        _wifiRttPermissionsGranted.postValue(granted)
    }

    // WIFI RTT AVAILABILITY MANAGEMENT
    private val _wifiRttEnabled = MutableLiveData<Boolean>(null)
    val wifiRttEnabled : LiveData<Boolean> get() = _wifiRttEnabled

    fun wifiRttEnabledUpdate(enabled : Boolean) {
        _wifiRttEnabled.postValue(enabled)
    }

    // WIFI RTT MEASURES MANAGEMENT
    private val accessPointList = mutableListOf<RangedAccessPoint>()
    private val _rangedAccessPoints = MutableLiveData(emptyList<RangedAccessPoint>())
    val rangedAccessPoints : LiveData<List<RangedAccessPoint>> = _rangedAccessPoints.map { l -> l.toList().map { el -> el.copy() } }

    // CONFIGURATION MANAGEMENT
    // TODO change map here
    private val _mapConfig = MutableLiveData(b30)
    val mapConfig : LiveData<MapConfig> get() = _mapConfig

    fun onNewRangingResults(newResults : List<RangingResult>) {
        //TODO we need to update encapsulated list of <RangedAccessPoint> in

        val currentTime = System.currentTimeMillis()

        for (result in newResults) {
            val existing = accessPointList.find { it.bssid == result.macAddress.toString() }

            if (existing != null) {
                existing.update(result)
            } else {
                val newAp = RangedAccessPoint.newInstance(result)
                newAp.update(result)
                accessPointList.add(newAp)
            }
        }

        accessPointList.removeAll { currentTime - it.age > 15000 }
        _rangedAccessPoints.postValue(accessPointList.toList())

        // when the list is updated, we also want to update estimated location
        estimateLocation()
    }

    // WIFI RTT ACCESS POINT LOCATIONS

    private val _estimatedPosition = MutableLiveData<DoubleArray>(null)
    val estimatedPosition : LiveData<DoubleArray> get() = _estimatedPosition

    private val _estimatedDistances = MutableLiveData<MutableMap<String, Double>>(mutableMapOf())
    val estimatedDistances : LiveData<Map<String, Double>> = _estimatedDistances.map { m -> m.toMap() }

    private val _debug = MutableLiveData(false)
    val debug : LiveData<Boolean> get() = _debug

    fun debugMode(debug: Boolean) {
        _debug.postValue(debug)
    }


    private fun estimateLocation() {
        // TODO we need to compute the estimated location by trilateration
        // the library https://github.com/lemmingapex/trilateration
        // will certainly helps you for the maths

        // you should post the coordinates [x, y, height] of the estimated position in _estimatedPosition
        // in the second experiment, you can hardcode the height as 0.0
       val currAccessPoints = accessPointList.filter { ap -> mapConfig.value?.accessPointKnownLocations?.get(ap.bssid) != null }.sortedBy { it.distanceMm }.take(4)

        val distances = mutableListOf<Double>()
        val positions = mutableListOf<DoubleArray>()

        currAccessPoints.forEach { ap ->
        val apLocation = mapConfig.value?.accessPointKnownLocations?.get(ap.bssid)
        if (apLocation != null) {
            distances.add(ap.distanceMm)
            positions.add(doubleArrayOf(apLocation.xMm.toDouble(), apLocation.yMm.toDouble(), apLocation.heightMm.toDouble()))
        }
        }

        val positionsArray = positions.toTypedArray()
        val distancesArray = distances.toDoubleArray()

        if (positionsArray.size > 2 ) {
            val solver = NonLinearLeastSquaresSolver(
                TrilaterationFunction(positionsArray, distancesArray),
                LevenbergMarquardtOptimizer()
            )
            val optimum = solver.solve()

            val centroid = optimum.point.toArray()
            Log.e(TAG, "Centroid: ${centroid.joinToString()}")
            _estimatedPosition.postValue(centroid)
        } else {
            Log.e(TAG, "Insufficient or mismatched data for trilateration")

        }



        val distanceMap = mutableMapOf<String, Double>()

        currAccessPoints.forEach { ap ->
            distanceMap[ap.bssid] = ap.distanceMm
        }
        _estimatedDistances.postValue(distanceMap)
    }

    companion object {
        private val TAG = WifiRttViewModel::class.simpleName
    }

}