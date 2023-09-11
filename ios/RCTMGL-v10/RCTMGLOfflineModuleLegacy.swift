import Foundation
import MapboxMaps

final class OfflineRegionObserverCustom: OfflineRegionObserver {
  func statusChanged(for status: OfflineRegionStatus) {
    print("\(status.completedResourceCount)/\(status.requiredResourceCount) resources; \(status.completedResourceSize) bytes downloaded.")
  }

  func responseError(forError error: ResponseError) {
    print("Offline resource download error: \(error.reason), \(error.message)")
  }

  func mapboxTileCountLimitExceeded(forLimit limit: UInt64) {
    print("Mapbox tile count max (\(limit)) has been exceeded!")
  }
}

@objc(RCTMGLOfflineModuleLegacy)
class RCTMGLOfflineModuleLegacy: RCTEventEmitter {
  static let RNMapboxInfoMetadataKey = "_rnmapbox"
  
  enum State : String {
      case invalid
      case inactive
      case active
      case complete
      case unknown
  }
  
  lazy var offlineRegionManager: OfflineRegionManager = {
      return OfflineRegionManager(resourceOptions: .init(accessToken: MGLModule.accessToken!))
  }()
  
  enum Callbacks : String {
    case error = "MapboOfflineRegionError"
    case progress = "MapboxOfflineRegionProgress"
  }

  
  
  
  @objc
  override
  func supportedEvents() -> [String] {
    return [Callbacks.error.rawValue, Callbacks.progress.rawValue]
  }

  func convertPointPairToBounds(_ bounds: Geometry) -> Geometry {
    guard case .geometryCollection(let gc) = bounds else {
      return bounds
    }
    let geometries = gc.geometries
    guard geometries.count == 2 else {
      return bounds
    }
    guard case .point(let g0) = geometries[0] else {
      return bounds
    }
    guard case .point(let g1) = geometries[1] else {
      return bounds
    }
    let pt0 = g0.coordinates
    let pt1 = g1.coordinates
    return .polygon(Polygon([
      [
        pt0,
        CLLocationCoordinate2D(latitude: pt0.latitude, longitude: pt1.longitude),
        pt1,
        CLLocationCoordinate2D(latitude: pt1.latitude, longitude: pt0.longitude)
      ]
    ]))
  }
  
  func convertPackToDict(region: OfflineRegion) -> [String: Any]? {
    let bb = RCTMGLFeatureUtils.boundingBox(geometry: region.getGeometryDefinition()!.geometry!)!
    let jsonBounds = [
       bb.northEast.longitude, bb.northEast.latitude,
       bb.southWest.longitude, bb.southWest.latitude
    ]
    
    let metadata = region.getMetadata()
    
    do {
        return [
            "metadata": String(data: metadata, encoding: .utf8),
            "bounds": jsonBounds
        ]
    } catch {
        return nil
    }
  }
  
  
  
  func createPackCallback(region: OfflineRegion,
                          metadata: Data,
                          resolver: @escaping RCTPromiseResolveBlock,
                          rejecter: @escaping RCTPromiseRejectBlock) {
    
    let observer = OfflineRegionObserverCustom()
      
    region.setOfflineRegionObserverFor(observer)
    region.setOfflineRegionDownloadStateFor(.active)
    region.setMetadata(metadata) { [weak self] result in
      switch result {
      case let .failure(error):
        print("Error creating offline region: \(error)")
        rejecter("createPack error", error.localizedDescription, error)

      case .success():
        resolver(self?.convertPackToDict(region: region))
      }
    }
  }
  
  // MARK: react methods
  
  @objc
  func createPack(_ options: NSDictionary, resolver: @escaping RCTPromiseResolveBlock, rejecter: @escaping RCTPromiseRejectBlock) {
    DispatchQueue.main.async {
      do {
        let metadataStr = options["metadata"] as! String
        let styleURL = options["styleURL"] as! String
        
        let boundsStr = options["bounds"] as! String
        let boundsData = boundsStr.data(using: .utf8)
        
        
        var boundsFC = try JSONDecoder().decode(FeatureCollection.self, from: boundsData!)
        
        var bounds = self.convertPointPairToBounds(RCTMGLFeatureUtils.fcToGeomtry(boundsFC))
        print("BOUNDS: bounds", bounds)
        
        let offlineRegionDef = OfflineRegionGeometryDefinition(
          styleURL: styleURL,
          geometry: bounds,
          minZoom: options["minZoom"] as! Double,
          maxZoom: options["maxZoom"] as! Double,
          pixelRatio: Float(UIScreen.main.scale),
          glyphsRasterizationMode: .ideographsRasterizedLocally)
      
        self.offlineRegionManager.createOfflineRegion(for: offlineRegionDef) { [weak self] result in
          switch result {
          case let .failure(error):
            print("Error creating offline region: \(error)")
            rejecter("createPack", error.localizedDescription, error)

          case let .success(region):
            self?.createPackCallback(region: region,
                                    metadata: metadataStr.data(using: .utf8)!,
                                    resolver: resolver,
                                    rejecter: rejecter)
          }
        }
      } catch {
        rejecter("createPack", error.localizedDescription, error)
      }
    }
  }
  
  @objc
  func getPacks(_ resolve : @escaping RCTPromiseResolveBlock, rejecter: @escaping RCTPromiseRejectBlock) {
    DispatchQueue.main.async {
      self.offlineRegionManager.offlineRegions { result in
        switch result {
        case .success(let regions):
          
          var payload = [Any]()

          
          for region in regions {
            self.convertPackToDict(region: region)
          }
          
          resolve(payload)
          
        case .failure(let error):
          rejecter("getPacks error", error.localizedDescription, error)
        }
      }
    }
  }
}
