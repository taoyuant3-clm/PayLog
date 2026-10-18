import CoreLocation
import Foundation

/// 取得一次目前位置並反查成中文地址（只在前景、開啟填寫畫面時使用）。
@MainActor
final class LocationService: NSObject, ObservableObject, CLLocationManagerDelegate {

    @Published var status: String = ""
    @Published var address: String?
    @Published var coordinate: CLLocationCoordinate2D?

    private let manager = CLLocationManager()
    private let geocoder = CLGeocoder()
    private var wantsLocation = false

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
    }

    func locate() {
        wantsLocation = true
        switch manager.authorizationStatus {
        case .notDetermined:
            status = "需要定位權限"
            manager.requestWhenInUseAuthorization()
        case .denied, .restricted:
            status = "未授權定位，請手動輸入"
            wantsLocation = false
        default:
            status = "定位中…"
            manager.requestLocation()
        }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        Task { @MainActor in
            guard self.wantsLocation else { return }
            switch manager.authorizationStatus {
            case .authorizedWhenInUse, .authorizedAlways:
                self.status = "定位中…"
                self.manager.requestLocation()
            case .denied, .restricted:
                self.status = "未授權定位，請手動輸入"
                self.wantsLocation = false
            default:
                break
            }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let loc = locations.last else { return }
        Task { @MainActor in
            self.wantsLocation = false
            self.coordinate = loc.coordinate
            self.status = String(format: "±%.0fm", loc.horizontalAccuracy)
            self.reverseGeocode(loc)
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        Task { @MainActor in
            self.wantsLocation = false
            self.status = "無法取得位置，請手動輸入"
        }
    }

    private func reverseGeocode(_ loc: CLLocation) {
        geocoder.cancelGeocode()
        geocoder.reverseGeocodeLocation(loc, preferredLocale: Locale(identifier: "zh_Hant_TW")) { [weak self] marks, _ in
            let text: String
            if let p = marks?.first {
                text = LocationService.format(p)
            } else {
                text = String(format: "%.5f, %.5f", loc.coordinate.latitude, loc.coordinate.longitude)
            }
            Task { @MainActor in
                self?.address = text
            }
        }
    }

    /// 台灣地址順序：縣市 + 區 + 路 + 號，若有地標名稱加在最後。
    nonisolated static func format(_ p: CLPlacemark) -> String {
        var s = ""
        for part in [p.administrativeArea, p.subAdministrativeArea, p.locality, p.subLocality,
                     p.thoroughfare, p.subThoroughfare] {
            if let part, !part.isEmpty, !s.contains(part) { s += part }
        }
        if let name = p.name, !name.isEmpty, !s.contains(name) {
            s += s.isEmpty ? name : "（\(name)）"
        }
        return s
    }
}
