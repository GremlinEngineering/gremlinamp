import SwiftUI
import AppKit

@MainActor final class AppDelegate:NSObject,NSApplicationDelegate,NSMenuDelegate {
    static weak var model:AppModel?
    static var mainWindow:NSWindow?
    private var statusItem:NSStatusItem?
    private var keyMonitor:Any?
    func applicationDidFinishLaunching(_ notification:Notification) {
        let item=NSStatusBar.system.statusItem(withLength:NSStatusItem.squareLength)
        item.button?.image=NSImage(systemSymbolName:"playpause.circle",accessibilityDescription:"GremlinAmp")
        let menu=NSMenu();menu.delegate=self;item.menu=menu;statusItem=item
        keyMonitor=NSEvent.addLocalMonitorForEvents(matching:.keyDown){event in
            if event.charactersIgnoringModifiers==" ",event.modifierFlags.intersection([.command,.control,.option]).isEmpty,!(NSApp.keyWindow?.firstResponder is NSTextView){Self.model?.player.toggle();return nil};return event
        }
    }
    func applicationShouldTerminateAfterLastWindowClosed(_ sender:NSApplication)->Bool {false}
    func applicationShouldHandleReopen(_ sender:NSApplication,hasVisibleWindows flag:Bool)->Bool {showWindow();return true}
    func menuWillOpen(_ menu:NSMenu) {
        menu.removeAllItems()
        if let title=Self.model?.player.title {let now=NSMenuItem(title:title,action:nil,keyEquivalent:"");now.isEnabled=false;menu.addItem(now)}
        add(menu,"Открыть GremlinAmp",#selector(showWindow));menu.addItem(.separator())
        add(menu,Self.model?.player.paused == false ? "Пауза" : "Играть",#selector(toggle))
        add(menu,"Предыдущая",#selector(previous));add(menu,"Следующая",#selector(next));menu.addItem(.separator())
        add(menu,"Завершить GremlinAmp",#selector(quit))
    }
    private func add(_ m:NSMenu,_ title:String,_ action:Selector) {let i=NSMenuItem(title:title,action:action,keyEquivalent:"");i.target=self;m.addItem(i)}
    @objc func showWindow() {NSApp.activate(ignoringOtherApps:true);(Self.mainWindow ?? NSApp.windows.first{$0.canBecomeMain})?.makeKeyAndOrderFront(nil)}
    @objc func toggle() {Self.model?.player.toggle()}
    @objc func previous() {Self.model?.player.previous()}
    @objc func next() {Self.model?.player.next()}
    @objc func quit() {NSApp.terminate(nil)}
    func applicationWillTerminate(_ notification:Notification) {Self.model?.player.stop()}
}

@main @MainActor struct GremlinAmpApp:App {
    @StateObject private var model=AppModel()
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    var body:some Scene {
        WindowGroup("GremlinAmp") {
            ContentView(model:model,player:model.player).onAppear{AppDelegate.model=model;if model.games.isEmpty {model.load()};DispatchQueue.main.async{if AppDelegate.mainWindow==nil{AppDelegate.mainWindow=NSApp.windows.first{$0.canBecomeMain};AppDelegate.mainWindow?.isReleasedWhenClosed=false}}}
        }.commands {
            CommandGroup(replacing:.newItem) {}
            CommandGroup(replacing:.appInfo) {Button("О программе GremlinAmp"){model.showAbout=true}}
            CommandGroup(replacing:.appSettings) {
                Button("Настройки…"){model.openSettings()}.keyboardShortcut(",",modifiers:.command)
                Button("Обновления GremlinAmp…"){model.openSettings(tab:3)}
            }
            PlaybackCommands(player: model.player)
        }
    }
}

@MainActor struct PlaybackCommands: Commands {
    @ObservedObject var player: Playback

    var body: some Commands {
        CommandMenu("Воспроизведение") {
            Button("Играть / пауза") { player.toggle() }
                .keyboardShortcut(.return, modifiers: .command)
            Button("Следующая") { player.next() }
                .keyboardShortcut(.rightArrow, modifiers: .command)
            Button("Предыдущая") { player.previous() }
                .keyboardShortcut(.leftArrow, modifiers: .command)
            Divider()
            Toggle("Перемешивать", isOn: $player.shuffle)
            Toggle("Повторять композицию", isOn: $player.repeatOne)
            Toggle("Повторять очередь", isOn: $player.loop)
        }
    }
}
