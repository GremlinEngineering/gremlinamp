import SwiftUI

@MainActor struct SettingsView: View {
    @ObservedObject var model: AppModel
    @ObservedObject var player: Playback
    @ObservedObject var startup: StartupSettings
    @ObservedObject var updates: ClientUpdates
    @State private var seriesSearch=""

    var body: some View {
        VStack(alignment:.leading,spacing:18) {
            HStack {
                Text("Настройки GremlinAmp").font(.title2).fontWeight(.semibold)
                Spacer()
                Button("Готово") { model.showSettings=false }.keyboardShortcut(.defaultAction)
            }
            Picker("Раздел",selection:$model.settingsTab) {
                Text("Приложение").tag(0)
                Text("Музыка").tag(1)
                Text("Очередь").tag(2)
                Text("Обновления клиента").tag(3)
            }.pickerStyle(.segmented)
            ScrollView {
                VStack(alignment:.leading,spacing:18) {
                    switch model.settingsTab {
                    case 1: music
                    case 2: queue
                    case 3: client
                    default: application
                    }
                }.frame(maxWidth:.infinity,alignment:.leading).padding(2)
            }
        }.padding(24).frame(width:680,height:650)
    }

    private var application: some View {
        VStack(alignment:.leading,spacing:16) {
            GroupBox("Запуск") {
                VStack(alignment:.leading,spacing:10) {
                    Toggle("Запускать GremlinAmp при входе в macOS",isOn:Binding(
                        get:{startup.enabled},set:{startup.setEnabled($0)}))
                    Text(startup.status).font(.caption).foregroundColor(.secondary)
                    if startup.needsApproval {
                        Button("Открыть настройки входа macOS") { startup.openSystemSettings() }
                    }
                }.frame(maxWidth:.infinity,alignment:.leading).padding(8)
            }
            GroupBox("Оформление") {
                Picker("Тема",selection:$model.theme) {
                    Text("Как в системе").tag("system")
                    Text("Светлая").tag("light")
                    Text("Тёмная").tag("dark")
                }.pickerStyle(.segmented).padding(8)
            }
            network
            Text("Фоновые проверки работают, пока GremlinAmp запущен. Закрытие окна оставляет приложение в строке меню. После сна пропущенная проверка выполняется при возобновлении работы.")
                .font(.caption).foregroundColor(.secondary)
        }
    }

    private var network: some View {
        GroupBox("Сеть") {
            VStack(alignment:.leading,spacing:8) {
                Toggle("Разрешать загрузки через сотовую и ограниченную сеть",isOn:$model.allowExpensiveNetwork)
                Text("По умолчанию используются Wi-Fi и Ethernet. Настройка действует для музыки, постеров, индекса и обновлений клиента; сохранённая музыка доступна без сети.")
                    .font(.caption).foregroundColor(.secondary)
            }.frame(maxWidth:.infinity,alignment:.leading).padding(8)
        }
    }

    private var music: some View {
        VStack(alignment:.leading,spacing:16) {
            GroupBox("Загрузка и обновление музыки") {
                VStack(alignment:.leading,spacing:10) {
                    Button("Скачать все мелодии с сайта") { model.downloadAll() }
                        .disabled(model.downloading || model.planningDownload || model.syncingMusic)
                    Text("Весь каталог независимо от поиска и фильтров. Перед загрузкой показывается объём недостающих файлов.")
                        .font(.caption).foregroundColor(.secondary)
                    Divider()
                    Toggle("Автоматически обновлять сохранённую музыку",isOn:$model.autoMusic)
                    Picker("Проверять",selection:$model.musicInterval) {
                        ForEach(SyncInterval.allCases) { Text($0.title).tag($0) }
                    }
                    Text(model.musicSyncStatus).font(.caption).foregroundColor(.secondary)
                    if let date=model.musicLastSuccess {
                        Text("Последнее обновление: \(date.formatted(date:.abbreviated,time:.shortened))")
                            .font(.caption).foregroundColor(.secondary)
                    }
                    HStack {
                        Button("Обновить сохранённое сейчас") { model.syncMusic() }
                            .disabled(model.downloading || model.planningDownload || model.syncingMusic)
                        if model.syncingMusic || model.downloading {
                            ProgressView().controlSize(.small)
                            Button("Остановить") { model.cancelDownload() }
                        }
                    }
                }.frame(maxWidth:.infinity,alignment:.leading).padding(8)
            }
            GroupBox("Новые игры") {
                VStack(alignment:.leading,spacing:10) {
                    Toggle("Следить за всей медиатекой",isOn:$model.followAll)
                    Text("При обновлении будут скачаны новые игры всей медиатеки или выбранных ниже серий. Сохранённые коллекции обновляются в любом случае.")
                        .font(.caption).foregroundColor(.secondary)
                    TextField("Найти серию",text:$seriesSearch)
                    if model.series.isEmpty {
                        Text("Серии появятся после загрузки каталога").foregroundColor(.secondary)
                    } else {
                        ScrollView {
                            LazyVStack(alignment:.leading,spacing:8) {
                                ForEach(model.series.filter{seriesSearch.isEmpty || $0.title.localizedCaseInsensitiveContains(seriesSearch)}) { series in
                                    Toggle(series.title,isOn:Binding(
                                        get:{model.followedSeries.contains(series.id)},
                                        set:{model.setFollowed(series.id,$0)}))
                                }
                            }
                        }.frame(height:160)
                    }
                }.frame(maxWidth:.infinity,alignment:.leading).padding(8)
            }
            network
        }
    }

    private var queue: some View {
        VStack(alignment:.leading,spacing:16) {
            GroupBox("Порядок воспроизведения") {
                VStack(alignment:.leading,spacing:10) {
                    Toggle("Перемешивать",isOn:$player.shuffle)
                    Picker("Повтор",selection:Binding(
                        get:{player.repeatOne ? "track" : (player.loop ? "queue" : "off")},
                        set:{value in player.repeatOne=value=="track";player.loop=value=="queue"})) {
                            Text("Выключен").tag("off")
                            Text("Композиция").tag("track")
                            Text("Очередь").tag("queue")
                        }.pickerStyle(.segmented)
                    Text("Повтор имеет приоритет перед продолжением. При перемешивании каждая композиция звучит один раз за проход.")
                        .font(.caption).foregroundColor(.secondary)
                }.frame(maxWidth:.infinity,alignment:.leading).padding(8)
            }
            GroupBox("После очереди") {
                VStack(alignment:.leading,spacing:10) {
                    ForEach(ContinuationMode.allCases) { mode in
                        Button {
                            model.continuation=mode
                        } label: {
                            HStack(alignment:.top,spacing:9) {
                                Image(systemName:model.continuation==mode ? "largecircle.fill.circle" : "circle")
                                VStack(alignment:.leading,spacing:3) {
                                    Text(mode.title)
                                    if let reason=model.similarityReasons[mode.rawValue] {
                                        Text(reason).font(.caption).foregroundColor(.secondary)
                                    }
                                }
                            }.contentShape(Rectangle())
                        }.buttonStyle(.plain).disabled(mode.acoustic && model.similarityReasons[mode.rawValue] != nil)
                    }
                    Divider()
                    Toggle("Продолжать только скачанной музыкой",isOn:$model.continueLocal)
                    Text("Учитываются платформа, год и длительность. Для подбора игр исключаются уже проигранные коллекции; для ритма и звучания — уже проигранные композиции.")
                        .font(.caption).foregroundColor(.secondary)
                }.frame(maxWidth:.infinity,alignment:.leading).padding(8)
            }
            GroupBox("Анализ музыки") {
                VStack(alignment:.leading,spacing:10) {
                    Text(model.indexStatus).font(.caption).foregroundColor(.secondary)
                    HStack {
                        Button("Обновить индекс с сайта") { model.refreshIndex() }.disabled(model.updatingIndex)
                        if model.updatingIndex { ProgressView().controlSize(.small) }
                    }
                    Text("Ритм и звучание подбираются по серверному анализу. Сохранённый индекс работает без сети. Похожие игры подбираются по серии, жанрам и платформе.")
                        .font(.caption).foregroundColor(.secondary)
                }.frame(maxWidth:.infinity,alignment:.leading).padding(8)
            }
        }
    }

    private var client: some View {
        VStack(alignment:.leading,spacing:16) {
            GroupBox("Новые версии GremlinAmp") {
                VStack(alignment:.leading,spacing:12) {
                    Text("Установлена версия \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "")")
                    Toggle("Автоматически скачивать обновления клиента",isOn:$updates.automatic)
                    Picker("Проверять",selection:$updates.interval) {
                        ForEach(SyncInterval.allCases) { Text($0.title).tag($0) }
                    }
                    Text(updates.status).foregroundColor(.secondary)
                    if let date=updates.lastCheck {
                        Text("Последняя проверка: \(date.formatted(date:.abbreviated,time:.shortened))")
                            .font(.caption).foregroundColor(.secondary)
                    }
                    HStack {
                        Button("Проверить и скачать сейчас") { updates.check() }.disabled(updates.busy)
                        if updates.busy {ProgressView().controlSize(.small)}
                    }
                    if updates.readyURL != nil {
                        HStack {
                            Button("Открыть DMG \(updates.readyVersion)") { updates.openDownloaded() }.disabled(updates.busy)
                            Button("Показать в Finder") { updates.revealDownloaded() }
                        }
                    }
                    Text("Загружается версия для вашего Mac. Размер и SHA-256 проверяются до открытия. Для установки откройте DMG и замените приложение в Applications.")
                        .font(.caption).foregroundColor(.secondary)
                }.frame(maxWidth:.infinity,alignment:.leading).padding(8)
            }
            network
        }
    }
}
