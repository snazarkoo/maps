package com.mapbox.rctmgl.modules

import android.os.Build
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
import com.mapbox.geojson.Point
import com.mapbox.maps.CoordinateBounds
import com.mapbox.maps.GlyphsRasterizationMode
import com.mapbox.maps.OfflineRegion
import com.mapbox.maps.OfflineRegionCallback
import com.mapbox.maps.OfflineRegionCreateCallback
import com.mapbox.maps.OfflineRegionDownloadState
import com.mapbox.maps.OfflineRegionManager
import com.mapbox.maps.OfflineRegionObserver
import com.mapbox.maps.OfflineRegionStatus
import com.mapbox.maps.OfflineRegionTilePyramidDefinition
import com.mapbox.maps.ResourceOptions
import com.mapbox.maps.ResponseError
import com.mapbox.rctmgl.utils.ConvertUtils
import com.mapbox.rctmgl.utils.Logger
import com.mapbox.rctmgl.utils.extensions.toGeometryCollection
import com.mapbox.rctmgl.utils.writableArrayOf
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.UnsupportedEncodingException
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlin.math.ceil


@ReactModule(name = RCTMGLOfflineModuleLegacy.REACT_CLASS)
class RCTMGLOfflineModuleLegacy(private val mReactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(
        mReactContext
    ) {
    companion object {
        const val REACT_CLASS = "RCTMGLOfflineModuleLegacy"
        const val LOG_TAG = "OfflineModuleLegacy"
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
        bounds: CoordinateBounds,
        options: ReadableMap
    ): OfflineRegionTilePyramidDefinition {
        return OfflineRegionTilePyramidDefinition.Builder()
            .styleURL(ConvertUtils.getString("styleURL", options, DEFAULT_STYLE_URL))
            .bounds(bounds)
            .minZoom(ConvertUtils.getDouble("minZoom", options, DEFAULT_MIN_ZOOM_LEVEL))
            .maxZoom(ConvertUtils.getDouble("maxZoom", options, DEFAULT_MAX_ZOOM_LEVEL))
            .pixelRatio(mReactContext.getResources().getDisplayMetrics().density)
            .glyphsRasterizationMode(GlyphsRasterizationMode.IDEOGRAPHS_RASTERIZED_LOCALLY)
            .build()
    }

    private fun convertPointPairToBounds(boundsFC: FeatureCollection): CoordinateBounds? {
        val geometryCollection = boundsFC.toGeometryCollection()
        val geometries = geometryCollection.geometries()
        if (geometries.size != 2) {
            return null
        }
        val pt0 = geometries.get(0) as Point?
        val pt1 = geometries.get(1) as Point?
        if (pt0 == null || pt1 == null) {
            return null
        }
        return CoordinateBounds(pt0, pt1)
    }

    private fun createPackCallback(promise: Promise, metadata: ByteArray): OfflineRegionCreateCallback {
        return OfflineRegionCreateCallback { expected ->
            if (expected.isValue) {
                expected.value?.let {
                    it.setOfflineRegionObserver(regionObserver)
                    it.setOfflineRegionDownloadState(OfflineRegionDownloadState.ACTIVE)
                    it.setMetadata(metadata) { expectedMetadata ->
                        if (expectedMetadata.isError) {
                            promise.reject("createPack error:", "Failed to setMetadata")
                        } else {
                            Log.d(LOG_TAG,  "createPack done:")
                            promise.resolve(fromOfflineRegion(it))
                        }
                    }
                }
            } else {
                Log.d(LOG_TAG,  "createPack error:")
                promise.reject("createPack error:", "Failed to create OfflineRegion")
            }
        }
    }


    private fun fromOfflineRegion(region: OfflineRegion): WritableMap? {
        val bb = region.tilePyramidDefinition?.bounds
        val map = Arguments.createMap()

        if (bb === null) return map

        val jsonBounds = writableArrayOf(
            writableArrayOf(bb.east(), bb.north()),
            writableArrayOf(bb.west(), bb.south())
        )

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
        val progressPercentage = if (status.requiredResourceCount > 0) status.completedResourceCount.toDouble() / status.requiredResourceCount.toDouble() else 0.0
        val percentage = ceil(progressPercentage * 100.0).coerceAtMost(100.0)
        val isCompleted = percentage == 100.0
        val downloadState = if (isCompleted) COMPLETE_REGION_DOWNLOAD_STATE else status.downloadState.ordinal

        Log.d(LOG_TAG, "STATUS ${status.toString()}")

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
    fun addListener(eventName: String?) {
        // Set up any upstream listeners or background tasks as necessary
    }

    @ReactMethod
    fun removeListeners(count: Int?) {
        // Remove upstream listeners, stop unnecessary background tasks
    }

    @ReactMethod
    @Throws(JSONException::class)
    fun createPack(options: ReadableMap, promise: Promise) {
        try {
            val metadataBytes: ByteArray? =
                getMetadataBytes(ConvertUtils.getString("metadata", options, ""))

            val boundsStr = options.getString("bounds")!!
            val boundsFC = FeatureCollection.fromJson(boundsStr)
            val bounds = convertPointPairToBounds(boundsFC)

            if (metadataBytes == null || bounds == null) {
                promise.reject("createPack error:", "No metadata or bounds set")
                return
            };

            val definition: OfflineRegionTilePyramidDefinition = makeDefinition(bounds, options)

            UiThreadUtil.runOnUiThread {
                offlineRegionManager.createOfflineRegion(definition, createPackCallback(promise, metadataBytes))
            }

        } catch (e: Throwable) {
            promise.reject("createPack error:", e)
        }
    }

    @ReactMethod
    fun getPacks(promise: Promise) {
        UiThreadUtil.runOnUiThread {
            offlineRegionManager.getOfflineRegions(object: OfflineRegionCallback {
                override fun run(expected: Expected<String, MutableList<OfflineRegion>>) {
                    if (expected.isValue) {
                        expected.value?.let {
                            val payload = Arguments.createArray()

                            for (region in it) {
                                Log.d(LOG_TAG, "getPacks done:$region")
                                payload.pushMap(fromOfflineRegion(region!!))
                            }

                            Log.d(LOG_TAG,  "getPacks done:" + it.size.toString())
                            promise.resolve(payload)
                        }
                    } else {
                        promise.reject("getPacks error:", expected.error)
                        Log.d(LOG_TAG,  "getPacks error:${expected.error}")
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
                                promise.reject("deleteRegion error:", purgeExpected.error);
                            } else {
                                promise.resolve(null);
                            }
                        }
                    }
                } else {
                    promise.reject("deleteRegion error:", regionsExpected.error);
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
                                promise.reject("invalidateRegion error:", expected.error);
                            } else {
                                promise.resolve(null);
                            }
                        }
                    }
                } else {
                    promise.reject("invalidateRegion error:", expected.error);
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
                               promise.reject("getPackStatus error:", expected.error);
                           }
                        }
                    }
                } else {
                    promise.reject("getPackStatus error:", expected.error);
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
                    promise.reject("pausePackDownload error:", expected.error);
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
                        promise.resolve(null);
                    }
                } else {
                    promise.reject("resumeRegionDownload error:", expected.error);
                }
            }
        }
    }

    @ReactMethod
    fun resetDatabase(promise: Promise) {
        UiThreadUtil.runOnUiThread {
            var purgedCount = 0
            offlineRegionManager.getOfflineRegions { expected ->
                if (expected.isValue) {
                    expected.value?.let { regions ->
                        if (regions.size == 0) promise.resolve(null)

                        for (region in regions) {
                            region.setOfflineRegionDownloadState(OfflineRegionDownloadState.INACTIVE)

                            region.purge { expected ->
                                if (expected.isError) {
                                    promise.reject("purge error:", expected.error);
                                } else {
                                    purgedCount++
                                    if (purgedCount == regions.size) {
                                        Log.d(LOG_TAG, "resetDatabase done: ${regions.size} packs were purged")
                                        promise.resolve(null)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    promise.reject("resetDatabase error:", expected.error);
                }
            }
        }
    }

    @ReactMethod
    fun migrateOfflineCache(promise: Promise) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Old and new cache file paths
            Log.d(LOG_TAG, "v10 cache moving started")
            val targetDirectoryPathName = mReactContext.filesDir.absolutePath + "/.mapbox/map_data"
            val sourcePathName = mReactContext.filesDir.absolutePath + "/mbgl-offline.db"
            val sourcePath = Paths.get(sourcePathName)
            val targetPath = Paths.get("$targetDirectoryPathName/map_data.db")

            try {
                val source = File(sourcePath.toString())

                if (!source.exists()) {
                    Log.d(LOG_TAG, "Nothing to migrate")
                    promise.resolve(null)
                    return
                }

                val directory = File(targetDirectoryPathName)

                directory.mkdirs()
                Files.move(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING)
                Log.d(LOG_TAG, "v10 cache directory created successfully")
                promise.resolve(null)
            } catch (e: Exception) {
                val mes = "${e}... file move unsuccessful"
                Log.d(LOG_TAG, mes)
                promise.reject(mes)
            }
        } else {
            val mes = "\"migrateOfflineCache only supported on api level 26 or later\""
            Logger.w(LOG_TAG, "migrateOfflineCache only supported on api level 26 or later")
            promise.reject(mes)
        }
    }
}