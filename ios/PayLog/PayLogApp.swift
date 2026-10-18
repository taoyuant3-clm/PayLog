import SwiftData
import SwiftUI
import UIKit
import UserNotifications

@main
struct PayLogApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var router = Router.shared

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(router)
                .tint(Color("AccentColor"))
        }
        .modelContainer(Store.container)
    }
}

/// 處理「點通知 → 打開該筆紀錄」。
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        let center = UNUserNotificationCenter.current()
        center.delegate = self
        center.requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
        return true
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        if let s = response.notification.request.content.userInfo["uid"] as? String,
           let uid = UUID(uuidString: s) {
            DispatchQueue.main.async { Router.shared.open(uid) }
        }
        completionHandler()
    }

    /// App 在前景時（自動化已打開填寫畫面）不再顯示備援通知。
    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([])
    }
}
