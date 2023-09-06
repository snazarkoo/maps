package com.mapbox.rctmgl.modules

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
import com.mapbox.bindgen.Value
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.Geometry
import com.mapbox.geojson.Point
import com.mapbox.geojson.Polygon
import com.mapbox.maps.GlyphsRasterizationMode
import com.mapbox.maps.OfflineRegion
import com.mapbox.maps.OfflineRegionCallback
import com.mapbox.maps.OfflineRegionCreateCallback
import com.mapbox.maps.OfflineRegionGeometryDefinition
import com.mapbox.maps.OfflineRegionManager
import com.mapbox.maps.ResourceOptions
import com.mapbox.rctmgl.utils.ConvertUtils
import com.mapbox.rctmgl.utils.extensions.calculateBoundingBox
import com.mapbox.rctmgl.utils.extensions.toGeometryCollection
import com.mapbox.rctmgl.utils.writableArrayOf
import org.json.JSONException
import org.json.JSONObject


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

    private val callback: OfflineRegionCreateCallback = OfflineRegionCreateCallback { expected ->
        if (expected.isValue) {
            expected.value?.let {
                Log.d(LOG_TAG,  "createPack done:")
            }
        } else {
            Log.d(LOG_TAG,  "createPack error:")
        }
    }

    @ReactMethod
    @Throws(JSONException::class)
    fun createPack(options: ReadableMap, promise: Promise) {
        Log.d(LOG_TAG, "my Message")
        try {
            val metadataStr = options.getString("metadata")!!
            val metadata = JSONObject(metadataStr)
            val metadataValue = Value.valueOf(metadataStr)
            val id = metadata.getString("name")!!

            val boundsStr = options.getString("bounds")!!
            val boundsFC = FeatureCollection.fromJson(boundsStr)
            val bounds = convertPointPairToBounds(boundsFC)

            val definition: OfflineRegionGeometryDefinition = makeDefinition(bounds, options)

            UiThreadUtil.runOnUiThread(object: Runnable {
                override fun run() {
                    offlineRegionManager.createOfflineRegion(definition, callback)
                }
            })

        } catch (e: Throwable) {
            promise.reject("createPack error:", e)
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

    @ReactMethod
    fun getPacks(promise: Promise) {
        Log.d(LOG_TAG,  "getPack start:")



        UiThreadUtil.runOnUiThread(object : Runnable {
            override fun run() {
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
            }) }
        })
    }
}