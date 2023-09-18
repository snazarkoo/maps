package com.mapbox.rctmgl.modules

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.UiThreadUtil
import com.facebook.react.bridge.WritableMap
import com.facebook.react.module.annotations.ReactModule
import com.mapbox.bindgen.Expected
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.Geometry
import com.mapbox.geojson.Point
import com.mapbox.geojson.Polygon
import com.mapbox.maps.GlyphsRasterizationMode
import com.mapbox.maps.OfflineRegion
import com.mapbox.maps.OfflineRegionCallback
import com.mapbox.maps.OfflineRegionCreateCallback
import com.mapbox.maps.OfflineRegionDownloadState
import com.mapbox.maps.OfflineRegionGeometryDefinition
import com.mapbox.maps.OfflineRegionManager
import com.mapbox.maps.OfflineRegionObserver
import com.mapbox.maps.OfflineRegionStatus
import com.mapbox.maps.ResourceOptions
import com.mapbox.maps.ResponseError
import com.mapbox.rctmgl.utils.ConvertUtils
import com.mapbox.rctmgl.utils.extensions.calculateBoundingBox
import com.mapbox.rctmgl.utils.extensions.toGeometryCollection
import com.mapbox.rctmgl.utils.writableArrayOf
import org.json.JSONException
import org.json.JSONObject
import java.io.UnsupportedEncodingException
import kotlin.math.ceil


@ReactModule(name = RCTMGLLegacyOfflineModule.REACT_CLASS)
class RCTMGLLegacyOfflineModule(private val mReactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(
        mReactContext
    ) {
    companion object {
        const val REACT_CLASS = "RCTMGLLegacyOfflineModule"
        const val LOG_TAG = "LegacyOfflineModule"
        const val DEFAULT_STYLE_URL = "mapbox://styles/mapbox/streets-v11"
        const val DEFAULT_MIN_ZOOM_LEVEL = 10.0
        const val DEFAULT_MAX_ZOOM_LEVEL = 20.0
        const val COMPLETE_REGION_DOWNLOAD_STATE = 2
    }

    override fun getName(): String {
        return REACT_CLASS
    }

    val offlineRegionManager: OfflineRegionManager by lazy {
        OfflineRegionManager(
            ResourceOptions.Builder()
                .accessToken(RCTMGLModule.getAccessToken(mReactContext))
                .build()
        )
    }

    private fun makeDefinition(
        geometry: Geometry,
        options: ReadableMap
    ): OfflineRegionGeometryDefinition {
        return OfflineRegionGeometryDefinition.Builder()
            .styleURL(ConvertUtils.getString("styleURL", options, DEFAULT_STYLE_URL))
            .geometry(geometry)
            .minZoom(ConvertUtils.getDouble("minZoom", options, DEFAULT_MIN_ZOOM_LEVEL))
            .maxZoom(ConvertUtils.getDouble("maxZoom", options, DEFAULT_MAX_ZOOM_LEVEL))
            .pixelRatio(mReactContext.getResources().getDisplayMetrics().density)
            .glyphsRasterizationMode(GlyphsRasterizationMode.IDEOGRAPHS_RASTERIZED_LOCALLY)
            .build()
    }

    private fun convertPointPairToBounds(boundsFC: FeatureCollection): Geometry {
        val geometryCollection = boundsFC.toGeometryCollection()
        val geometries = geometryCollection.geometries()
        if (geometries.size != 2) {
            return geometryCollection
        }
        val g0 = geometries.get(0) as Point?
        val g1 = geometries.get(1) as Point?
        if (g0 == null || g1 == null) {
            return geometryCollection
        }
        val pt0 = g0
        val pt1 = g1
        return Polygon.fromLngLats(
            listOf(
                listOf(
                    pt0,
                    Point.fromLngLat(pt1.longitude(), pt0.latitude()),
                    pt1,
                    Point.fromLngLat(pt0.longitude(), pt1.latitude()),
                    pt0
                ))
        )
    }

    private fun createPackCallback(promise: Promise, metadata: ByteArray): OfflineRegionCreateCallback {
        return OfflineRegionCreateCallback { expected ->
            if (expected.isValue) {
                expected.value?.let {
                    it.setOfflineRegionObserver(regionObserver)
                    it.setOfflineRegionDownloadState(OfflineRegionDownloadState.ACTIVE)
                    it.setMetadata(metadata) { expectedMetadata ->
                        if (expectedMetadata.isError) {
                            promise.reject("createPack error", "Failed to setMetadata")
                        } else {
                            Log.d(LOG_TAG,  "createPack done:")
                            promise.resolve(fromOfflineRegion(it))
                        }
                    }
                }
            } else {
                Log.d(LOG_TAG,  "createPack error:")
                promise.reject("createPack error", "Failed to create OfflineRegion")
            }
        }
    }


    private fun fromOfflineRegion(region: OfflineRegion): WritableMap? {

        val bb = region.geometryDefinition!!.geometry.calculateBoundingBox()

        val jsonBounds = writableArrayOf(
            bb.northeast().longitude(),
            bb.northeast().latitude(),
            bb.southwest().longitude(),
            bb.southwest().latitude()
        )
        val map = Arguments.createMap()
        map.putArray("bounds", jsonBounds)
        map.putString("metadata", String(region.metadata))
        return map
    }

    private fun getMetadataBytes(metadata: String?): ByteArray? {
        var metadataBytes: ByteArray? = null
        if (metadata == null || metadata.isEmpty()) {
            return metadataBytes
        }
        try {
            metadataBytes = metadata.toByteArray(charset("utf-8"))
        } catch (e: UnsupportedEncodingException) {
            Log.w(LOG_TAG, e.localizedMessage)
        }
        return metadataBytes
    }

    private val regionObserver: OfflineRegionObserver = object : OfflineRegionObserver {
        override fun responseError(error: ResponseError) {
            Log.d(LOG_TAG, "Error downloading some resources:  ${error}, ${error.message}")
        }

        override fun statusChanged(status: OfflineRegionStatus) {
            Log.d(LOG_TAG,
                "${status.completedResourceCount}/${status.requiredResourceCount} resources; ${status.completedResourceSize} bytes downloaded."
            )
        }

        override fun mapboxTileCountLimitExceeded(Limit: Long) {
            Log.d(LOG_TAG, "mapboxTileCountLimitExceeded")
        }
    }

    private fun getRegionByName(
        name: String?,
        offlineRegions: List<OfflineRegion>
    ): OfflineRegion? {
        if (name.isNullOrEmpty()) {
            return null
        }
        for (region in offlineRegions) {
            try {
                val byteMetadata = region.metadata

                if (byteMetadata != null) {
                    val metadata = JSONObject(String(byteMetadata))
                    if (name == metadata.getString("name")) {
                        return region
                    }
                }
            } catch (e: JSONException) {
                Log.w(LOG_TAG, e.localizedMessage)
            }
        }
        return null
    }

    private fun makeRegionStatus(regionName: String, status: OfflineRegionStatus): WritableMap? {
        val map = Arguments.createMap()
        val  progressPercentage = if (status.requiredResourceCount > 0) status.completedResourceCount / status.requiredResourceCount else 0.0
        val percentage = ceil(progressPercentage.toDouble() * 100.0 )
        val isCompleted = percentage == 100.0
        val downloadState = if (isCompleted) COMPLETE_REGION_DOWNLOAD_STATE else status.downloadState.ordinal

        map.putString("name", regionName)
        map.putInt("state", downloadState)
        map.putDouble("percentage", percentage)
        map.putInt("completedResourceCount", status.completedResourceCount.toInt())
        map.putInt("completedResourceSize", status.completedResourceSize.toInt())
        map.putInt("completedTileSize", status.completedTileSize.toInt())
        map.putInt("completedTileCount", status.completedTileCount.toInt())
        map.putInt("requiredResourceCount", status.requiredResourceCount.toInt())
        return map
    }

    @ReactMethod
    @Throws(JSONException::class)
    fun createPack(options: ReadableMap, promise: Promise) {
        Log.d(LOG_TAG, "my Message")
        try {
            val metadataBytes: ByteArray? =
                getMetadataBytes(ConvertUtils.getString("metadata", options, ""))

            val boundsStr = options.getString("bounds")!!
            val boundsFC = FeatureCollection.fromJson(boundsStr)
            val bounds = convertPointPairToBounds(boundsFC)

            val definition: OfflineRegionGeometryDefinition = makeDefinition(bounds, options)

            if (metadataBytes == null) {
                promise.reject("createPack error:", "No metadata set")
                return
            };

            UiThreadUtil.runOnUiThread {
                offlineRegionManager.createOfflineRegion(definition, createPackCallback(promise, metadataBytes))
            }

        } catch (e: Throwable) {
            promise.reject("createPack error:", e)
        }
    }

    @ReactMethod
    fun getPacks(promise: Promise) {
        Log.d(LOG_TAG,  "getPack start:")

        UiThreadUtil.runOnUiThread {
            offlineRegionManager.getOfflineRegions(object: OfflineRegionCallback {
                override fun run(expected: Expected<String, MutableList<OfflineRegion>>) {
                    if (expected.isValue) {
                        expected.value?.let {
                            val payload = Arguments.createArray()

                            for (region in it) {
                                payload.pushMap(fromOfflineRegion(region!!))
                            }

                            Log.d(LOG_TAG,  "getPacks done:" + it.size.toString())
                            promise.resolve(payload)
                        }
                    } else {
                        promise.reject("getPacks", expected.error)
                        Log.d(LOG_TAG,  "getPacks error:")
                    }
                }
            })
        }
    }

    @ReactMethod
    fun deletePack(name: String?, promise: Promise) {
        UiThreadUtil.runOnUiThread {
            offlineRegionManager.getOfflineRegions { regionsExpected ->
                if (regionsExpected.isValue) {
                    regionsExpected.value?.let { regions ->
                        var region = getRegionByName(name, regions);

                        if (region == null) {
                            promise.resolve(null);
                            Log.w(LOG_TAG, "deleteRegion - Unknown offline region");
                            return@getOfflineRegions
                        }

                        region.setOfflineRegionDownloadState(OfflineRegionDownloadState.INACTIVE)
                        
                        region.purge { purgeExpected ->
                            if (purgeExpected.isError) {
                                promise.reject("deleteRegion", purgeExpected.error);
                            } else {
                                promise.resolve(null);
                            }
                        }
                    }
                } else {
                    promise.reject("deleteRegion", regionsExpected.error);
                }
            }
        }
    }

    @ReactMethod
    fun invalidatePack(name: String?, promise: Promise) {
        UiThreadUtil.runOnUiThread {
            offlineRegionManager.getOfflineRegions { expected ->
                if (expected.isValue) {
                    expected.value?.let { regions ->
                        var region = getRegionByName(name, regions);

                        if (region == null) {
                            promise.resolve(null);
                            Log.w(LOG_TAG, "invalidateRegion - Unknown offline region");
                            return@getOfflineRegions
                        }

                        region.invalidate { expected ->
                            if (expected.isError) {
                                promise.reject("invalidateRegion", expected.error);
                            } else {
                                promise.resolve(null);
                            }
                        }
                    }
                } else {
                    promise.reject("invalidateRegion", expected.error);
                }
            }
        }
    }

    @ReactMethod
    fun getPackStatus(name: String?, promise: Promise) {
        UiThreadUtil.runOnUiThread {
            offlineRegionManager.getOfflineRegions { expected ->
                if (expected.isValue) {
                    expected.value?.let { regions ->
                        var region = getRegionByName(name, regions);

                        if (region == null) {
                            promise.resolve(null);
                            Log.w(LOG_TAG, "getPackStatus - Unknown offline region");
                            return@getOfflineRegions
                        }

                        region.getStatus {
                           if (it.isValue) {
                               it.value?.let { status ->
                                   promise.resolve(makeRegionStatus(name!!, status));
                               }
                           } else {
                               promise.reject("getPackStatus", expected.error);
                           }
                        }
                    }
                } else {
                    promise.reject("getPackStatus", expected.error);
                }
            }
        }
    }

    @ReactMethod
    fun pausePackDownload(name: String?, promise: Promise) {
        UiThreadUtil.runOnUiThread {
            offlineRegionManager.getOfflineRegions { expected ->
                if (expected.isValue) {
                    expected.value?.let { regions ->
                        var region = getRegionByName(name, regions);

                        if (region == null) {
                            promise.resolve(null);
                            Log.w(LOG_TAG, "pausePackDownload - Unknown offline region");
                            return@getOfflineRegions
                        }

                        Handler(Looper.getMainLooper()).post(Runnable {
                            region.setOfflineRegionDownloadState(OfflineRegionDownloadState.INACTIVE)
                            promise.resolve(null)
                        })
                    }
                } else {
                    promise.reject("pausePackDownload", expected.error);
                }
            }
        }
    }

    @ReactMethod
    fun resumePackDownload(name: String?, promise: Promise) {
        UiThreadUtil.runOnUiThread {
            offlineRegionManager.getOfflineRegions { expected ->
                if (expected.isValue) {
                    expected.value?.let { regions ->
                        var region = getRegionByName(name, regions);

                        if (region == null) {
                            promise.resolve(null);
                            Log.w(LOG_TAG, "resumeRegionDownload - Unknown offline region");
                            return@getOfflineRegions
                        }

                        region.setOfflineRegionDownloadState(OfflineRegionDownloadState.ACTIVE)
                    }
                } else {
                    promise.reject("resumeRegionDownload", expected.error);
                }
            }
        }
    }
}