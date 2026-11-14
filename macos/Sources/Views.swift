import SwiftUI
import AppKit

private let terminalAccent = TerminalColors.accent
private let panel = TerminalColors.panel

@MainActor struct ArtworkView:View {
    let library:LibraryStore;let id:String;var series=false
    @State private var image:NSImage?
    var body:some View {
        ZStack {RoundedRectangle(cornerRadius:5).fill(Color.primary.opacity(0.06))
            if let img=image {Image(nsImage:img).resizable().aspectRatio(contentMode:.fit)}
            else {Image(systemName:"music.note.list").foregroundColor(.secondary)}
        }.task(id:"\(series):\(id)") {
            image=nil
            if let url=try? await library.artwork(id:id,series:series,preview:true),!Task.isCancelled {image=NSImage(contentsOf:url)}
        }
    }
}
@MainActor struct PlayerPanel: View {
    @ObservedObject var player: Playback
    @ObservedObject var model: AppModel
    @State private var sliding = false
    @State private var slider: Double = 0
    @State private var settings: TrackerMode?
    @State private var sleep = false

    var body: some View {
        VStack(spacing: 12) {
            HStack(alignment: .center, spacing: 16) {
                artwork
                VStack(alignment: .leading, spacing: 5) {
                    Text(player.state).font(.system(.caption, design: .monospaced)).foregroundColor(terminalAccent)
                    Text(player.title ?? "Музыка любимых игр").font(.system(.title2, design: .monospaced).weight(.semibold))
                        .lineLimit(2).frame(maxWidth: .infinity, alignment: .leading)
                    Text(player.detail ?? "Аркадный зал Школы Империи")
                        .font(.subheadline).foregroundColor(TerminalColors.muted).lineLimit(1)
                    Text(player.liveLabel).font(.system(.headline, design: .monospaced)).foregroundColor(terminalAccent)
                    if player.trackerMode == .weather || player.trackerMode == .orbits {
                        Text(player.signalSummary).font(.system(.caption, design: .monospaced))
                            .foregroundColor(TerminalColors.muted).lineLimit(2)
                    }
                }
                TerminalSpectrum(levels: player.spectrum)
            }
            if player.canSeek || sliding {
                Slider(value: Binding(get: { sliding ? slider : Double(player.elapsed) }, set: { slider = $0 }),
                       in: 0...Double(max(1, player.duration ?? 1)), onEditingChanged: { editing in
                           if editing { slider = Double(player.elapsed); sliding = true }
                           else { sliding = false; player.seek(Int(slider)) }
                       }).accessibilityLabel("Позиция композиции")
            }
            if player.current != nil { arrangement }
            controls
        }.padding(15).background(panel, in: RoundedRectangle(cornerRadius: 9))
            .overlay(RoundedRectangle(cornerRadius: 9).stroke(TerminalColors.line).allowsHitTesting(false))
            .sheet(item: $settings) { TrackerSettingsView(mode: $0, player: player) }
            .background(EmptyView().sheet(isPresented: $sleep) { SleepTimerView(player: player) })
    }

    private var arrangement: some View {
        HStack(spacing: 10) {
            Toggle(isOn: Binding(get:{player.arrangementEnabled},set:{player.setArrangement(enabled:$0)})) {
                Label("Трекер для очереди",systemImage:"waveform.path")
            }.toggleStyle(.button)
            if player.arrangementEnabled {
                Menu {
                    Picker("Стиль",selection:Binding(get:{player.arrangementStyle},set:{player.setArrangement(style:$0)})) {
                        ForEach(QueueStyle.allCases) {Text($0.title).tag($0)}
                    }
                    Picker("Длительность",selection:Binding(get:{player.arrangementLength},set:{player.setArrangement(length:$0)})) {
                        ForEach(QueueLength.allCases) {Text($0.title).tag($0)}
                    }
                    Button("Новый вариант") {player.setArrangement(variation:true)}
                } label: {
                    Text(player.arrangementStyle.title+" · "+player.arrangementLength.title)
                }.fixedSize().help("Стиль и длина аранжировок всей очереди")
                if let active=player.arrangementActive {
                    Text("\(active.title) · \(Int(active.bpm)) BPM").foregroundColor(terminalAccent)
                } else if let notice=player.arrangementNotice {
                    Text(notice).foregroundColor(TerminalColors.muted)
                }
            }
            Spacer(minLength:0)
        }.font(.system(.caption,design:.monospaced))
    }

    private var artwork: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 6).fill(TerminalColors.background)
            if let art = player.artwork { Image(nsImage: art).resizable().aspectRatio(contentMode: .fit) }
            else { Image(systemName: player.trackerMode != nil ? "waveform" : player.station != nil ? "radio" : "music.note")
                .font(.system(size: 26)).foregroundColor(terminalAccent) }
        }.frame(width: 70, height: 82).onTapGesture {
            if let track = player.current, !track.gameID.isEmpty { model.showPoster(id: track.gameID) }
        }
    }

    private var controls: some View {
        HStack(spacing: 12) {
            if player.current != nil {
                Toggle(isOn: $player.shuffle) { Image(systemName: "shuffle") }.toggleStyle(.button).help("Случайный порядок")
                Button { player.previous() } label: { Image(systemName: "backward.end.fill") }.help("Предыдущая композиция")
                Button { player.seek(player.elapsed - 10_000) } label: { Image(systemName: "gobackward.10") }
                    .disabled(!player.canSeek).help("Назад на 10 секунд")
            }
            Button { player.toggle() } label: {
                Image(systemName: player.paused ? "play.fill" : "pause.fill").font(.system(size: 17))
                    .frame(width: 44, height: 32).background(terminalAccent, in: RoundedRectangle(cornerRadius: 5))
                    .foregroundColor(TerminalColors.onAccent)
            }.buttonStyle(.plain).disabled(!player.playable).help("Играть / пауза")
            if player.current != nil {
                Button { player.seek(player.elapsed + 10_000) } label: { Image(systemName: "goforward.10") }
                    .disabled(!player.canSeek).help("Вперёд на 10 секунд")
                Button { player.next() } label: { Image(systemName: "forward.end.fill") }.help("Следующая композиция")
                Toggle(isOn: $player.repeatOne) { Image(systemName: "repeat.1") }.toggleStyle(.button).help("Повторять композицию")
                Toggle(isOn: $player.loop) { Image(systemName: "repeat") }.toggleStyle(.button).help("Повторять очередь")
            }
            if let mode = player.trackerMode {
                Button { settings = mode } label: { Label("Настройки режима", systemImage: "gearshape") }
            }
            if player.playable {
                Button { sleep = true } label: {
                    Label(player.sleepRemaining.map { stamp($0 * 1000) } ?? "Таймер сна", systemImage: "moon")
                }
            }
            Spacer()
            Image(systemName: "speaker.wave.2.fill").foregroundColor(TerminalColors.muted)
            Slider(value: $player.volume, in: 0...1).frame(width: 85).accessibilityLabel("Громкость")
        }.buttonStyle(.borderless)
    }
}
@MainActor struct TrackRow:View {
    let track:Track;let active:Bool;let action:()->Void
    var body:some View {
        Button(action:action){HStack(spacing:12){Image(systemName:active ? "speaker.wave.2.fill" : "music.note").foregroundColor(active ? terminalAccent : .secondary).frame(width:20)
            VStack(alignment:.leading,spacing:4){Text(track.title).font(.system(.body, design: .monospaced)).lineLimit(1);Text(track.artist.isEmpty ? track.album : track.artist+" · "+track.album).font(.caption).foregroundColor(.secondary).lineLimit(1)}
            Spacer();Text(track.format.uppercased()).font(.system(size:10,design:.monospaced)).foregroundColor(.secondary);Text(track.totalMS.map(stamp) ?? "--:--").font(.system(size:12,design:.monospaced)).foregroundColor(.secondary).frame(width:60,alignment:.trailing)
        }.padding(.vertical,9).contentShape(Rectangle())}.buttonStyle(.plain)
    }
}
@MainActor struct GameRow:View {
    @ObservedObject var model:AppModel;let game:Game
    var body:some View {
        HStack(spacing:14){ArtworkView(library:model.library,id:game.id).frame(width:48,height:65).onTapGesture{model.showPoster(id:game.id)}
            Button{model.open(game)}label:{VStack(alignment:.leading,spacing:5){Text(game.title).font(.headline);Text(game.platform+(game.year.map{" · \($0)"} ?? "")+" · \(game.count) треков").font(.caption).foregroundColor(.secondary)}.frame(maxWidth:.infinity,alignment:.leading).contentShape(Rectangle())}.buttonStyle(.plain)
            if let state=model.downloadStates[game.id],state != .download {
                Text(state.title).font(.caption).foregroundColor(.secondary)
            }
            Menu {
                Button("Играть всё"){model.playGames([game])}
                Button("Добавить в очередь"){model.playGames([game],append:true)}
                Button(model.downloadStates[game.id]?.title ?? "Скачать"){model.planDownload([game])}
                    .disabled(model.downloadStates[game.id] == .downloaded || model.downloading || model.planningDownload || model.syncingMusic)
                Button("Открыть постер"){model.showPoster(id:game.id)}
            }label:{Image(systemName:"ellipsis")}.menuStyle(.borderlessButton).frame(width:28)
        }.padding(.vertical,5)
    }
}
@MainActor struct SeriesRow: View {
    @ObservedObject var model: AppModel
    let series: MusicSeries

    var body: some View {
        HStack(spacing: 14) {
            ArtworkView(library: model.library, id: series.id, series: true)
                .frame(width: 48, height: 65)
                .onTapGesture { model.showPoster(id: series.id, series: true) }

            Button {
                model.open(series)
            } label: {
                VStack(alignment: .leading, spacing: 5) {
                    Text(series.title).font(.headline)
                    Text("Игр с музыкой: \(model.members(series).count)")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            Menu {
                Button("Играть всё") { model.playGames(model.members(series)) }
                Button("Добавить в очередь") {
                    model.playGames(model.members(series), append: true)
                }
                Button("Скачать") { model.planDownload(model.members(series),seriesID:series.id) }
                    .disabled(model.downloading || model.planningDownload || model.syncingMusic)
                Toggle("Следить за новыми играми",isOn:Binding(
                    get:{model.followedSeries.contains(series.id)},set:{model.setFollowed(series.id,$0)}))
                Button("Постер серии") { model.showPoster(id: series.id, series: true) }
            } label: {
                Image(systemName: "ellipsis")
            }
            .menuStyle(.borderlessButton)
            .frame(width: 28)
        }
        .padding(.vertical, 5)
    }
}

@MainActor struct LibraryBrowser:View {
    @ObservedObject var model:AppModel
    @ObservedObject var player:Playback
    var body:some View {
        VStack(alignment:.leading,spacing:12) {
            HStack {if model.selectedGame != nil{Button{model.back()}label:{Image(systemName:"chevron.left")}.help("К списку игр")}
                Text(model.title).font(.system(.title2, design: .monospaced).weight(.semibold)).lineLimit(1);Spacer()
                if model.section == .queue {
                    Button("Настройки очереди"){model.openSettings(tab:2)}
                    Button("Очистить"){player.clear()}.disabled(player.queue.isEmpty)
                } else {
                    Button("Играть всё"){model.playVisible()}
                    Button(model.selectionDownloadTitle){model.selectDownloads()}
                        .disabled(model.downloading || model.planningDownload || model.syncingMusic || model.selectionDownloadTitle=="Скачано")
                }
            }
            if model.section != .queue {
                HStack {
                    HStack{Image(systemName:"magnifyingglass").foregroundColor(.secondary);TextField("Найти игру, серию или композицию",text:$model.search).textFieldStyle(.plain);if !model.search.isEmpty{Button{model.search=""}label:{Image(systemName:"xmark.circle.fill")}.buttonStyle(.plain)}}.padding(8).background(Color.primary.opacity(0.05),in:RoundedRectangle(cornerRadius:8))
                    Menu {Picker("Порог",selection:$model.shortSeconds){Text("Без порога").tag(0);Text("От 5 секунд").tag(5);Text("От 10 секунд").tag(10);Text("От 15 секунд").tag(15)};Divider();Picker("Неизвестная длительность",selection:$model.unknown){Text("Показывать неизвестные").tag("show");Text("Скрывать неизвестные").tag("hide");Text("Только неизвестные").tag("only")}}label:{Label(model.shortSeconds==0 ? "Длительность" : "От \(model.shortSeconds) с",systemImage:"line.3.horizontal.decrease.circle")}
                }
                HStack {
                    Picker("Платформа",selection:$model.platform){Text("Все платформы").tag("");ForEach(model.platforms,id:\.self){Text($0).tag($0)}}.frame(maxWidth:250);Picker("Год",selection:$model.year){Text("Все годы").tag("");ForEach(model.years,id:\.self){Text($0).tag($0)}}.frame(maxWidth:170)
                    Spacer();Button("Сбросить"){model.resetFilters()}.buttonStyle(.link)
                }
                ScrollView(.horizontal,showsIndicators:false){HStack(spacing:2){letterButton("Все",value:"");ForEach(model.letters,id:\.self){letterButton($0,value:$0)}}}
            }
            rows
        }
    }
    private func letterButton(_ text:String,value:String) -> some View {Button{model.letter=value}label:{Text(text).font(.system(size:11,weight:.medium,design:.monospaced)).padding(.horizontal,9).padding(.vertical,5).background(model.letter==value ? terminalAccent.opacity(0.18) : .clear,in:RoundedRectangle(cornerRadius:5)).foregroundColor(model.letter==value ? terminalAccent : .secondary)}.buttonStyle(.plain)}
    @ViewBuilder private var rows: some View {
        if model.section == .queue {
            if player.queue.isEmpty {
                empty("Очередь пуста", "Включите музыку или добавьте композиции в очередь.")
            } else {
                List {
                    ForEach(Array(player.queue.enumerated()), id: \.offset) { index, track in
                        TrackRow(track: track, active: index == player.index) {
                            player.jump(index)
                        }
                        .contextMenu {
                            Button("Убрать из очереди") { player.remove(index) }
                        }
                    }
                }
                .listStyle(.inset)
            }
        } else if model.selectedGame != nil {
            if model.visibleTracks.isEmpty {
                empty("Нет композиций", "Измените фильтры или дождитесь загрузки треклиста.")
            } else {
                List(model.visibleTracks) { track in
                    TrackRow(track: track, active: player.current?.id == track.id) {
                        model.playTrack(track)
                    }
                    .contextMenu {
                        Button("Добавить в очередь") { player.append([track]) }
                        Button("Сведения") { trackInfo(track) }
                    }
                }
                .listStyle(.inset)
            }
        } else if model.section == .series {
            if model.visibleSeries.isEmpty {
                empty("Нет серий с музыкой", "Проверьте фильтры или обновите каталог.")
            } else {
                List(model.visibleSeries) { series in
                    SeriesRow(model: model, series: series)
                }
                .listStyle(.inset)
            }
        } else {
            if model.visibleGames.isEmpty {
                empty(
                    model.busy ? "Загрузка каталога…" : "Нет подходящих игр",
                    model.section == .downloaded
                        ? "Скачайте музыку игры или серии из каталога."
                        : "Измените поиск или фильтры."
                )
            } else {
                List(model.visibleGames) { game in
                    GameRow(model: model, game: game)
                }
                .listStyle(.inset)
            }
        }
    }
    private func empty(_ title:String,_ detail:String) -> some View {VStack(spacing:12){Image(systemName:"music.note.list").font(.system(size:32)).foregroundColor(.secondary);Text(title).font(.headline);Text(detail).foregroundColor(.secondary).multilineTextAlignment(.center)}.frame(maxWidth:.infinity,maxHeight:.infinity)}
    private func trackInfo(_ t:Track) {let a=NSAlert();a.messageText=t.title;a.informativeText="\(t.album)\n\(t.artist)\nФормат: \(t.format.uppercased())\nДлительность: \(t.totalMS.map(stamp) ?? "неизвестна")\nВнутренний номер: \(t.subsong)";a.runModal()}
}
@MainActor struct ContentView:View {
    @ObservedObject var model:AppModel
    @ObservedObject var player:Playback
    var body:some View {
        HStack(spacing:0) {
            VStack(alignment:.leading,spacing:24) {
                HStack(spacing:8){Image(systemName:"terminal").font(.system(size:24)).foregroundColor(terminalAccent);Text("GremlinAmp").font(.system(size:13,weight:.semibold,design:.monospaced))}.padding(.bottom,8)
                VStack(spacing:6){ForEach(LibrarySection.allCases){s in Button{model.navigate(s)}label:{Label(s.rawValue,systemImage:icon(s)).font(.system(size:13,weight:model.section==s ? .semibold : .regular)).frame(maxWidth:.infinity,alignment:.leading).padding(10).background(model.section==s ? terminalAccent.opacity(0.15) : .clear,in:RoundedRectangle(cornerRadius:7)).foregroundColor(model.section==s ? terminalAccent : .primary)}.buttonStyle(.plain)}}
                Spacer()
                if model.downloading || model.syncingMusic || model.planningDownload {ProgressView().controlSize(.small);Button("Остановить загрузку"){model.cancelDownload()}.font(.caption)}
                Button {model.downloadAll()} label: {Label("Скачать всё",systemImage:"arrow.down.to.line")}
                    .buttonStyle(.plain).disabled(model.downloading || model.planningDownload || model.syncingMusic)
                    .help("Скачать все мелодии сайта независимо от фильтров")
                Button {model.openSettings()} label: {Label("Настройки",systemImage:"gearshape")}.buttonStyle(.plain)
                Button{model.showAbout=true}label:{Label("О проекте",systemImage:"info.circle")}.buttonStyle(.plain).foregroundColor(.secondary)
                Text("GremlinAmp · " + (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "macOS")).font(.system(size:10,design:.monospaced)).foregroundColor(.secondary)
            }.padding(20).frame(width:175).background(TerminalColors.panel)
            Divider()
            VStack(spacing:18) {
                PlayerPanel(player:player,model:model)
                Group {
                    switch model.section {
                    case .tracker: TrackerBrowser(player: player)
                    case .radio: RadioBrowser(player: player)
                    default: LibraryBrowser(model:model,player:player)
                    }
                }.frame(maxWidth:.infinity,maxHeight:.infinity)
                HStack{if model.busy{ProgressView().controlSize(.small)};Text(model.downloading ? model.downloadStatus : model.syncingMusic ? model.musicSyncStatus : model.status).font(.caption).foregroundColor(.secondary).lineLimit(2);Spacer();Button{model.load()}label:{Image(systemName:"arrow.clockwise")}.buttonStyle(.plain).disabled(model.busy).help("Обновить каталог")}
            }.padding(22).frame(minWidth:720)
        }.frame(minWidth:940,minHeight:710)
            .background(TerminalColors.background).tint(terminalAccent).foregroundColor(TerminalColors.primary)
            .preferredColorScheme(model.theme=="dark" ? .dark : model.theme=="light" ? .light : nil)
            .sheet(isPresented:$model.showAbout){AboutView(model:model)}
            .background(EmptyView().sheet(isPresented:$model.showSettings){SettingsView(model:model,player:player,startup:model.startup,updates:model.clientUpdates)})
            .background(EmptyView().sheet(item:$model.poster){doc in PosterZoom(document:doc)})
    }
    private func icon(_ s:LibrarySection)->String {switch s{case .games:return "gamecontroller";case .series:return "square.stack";case .downloaded:return "arrow.down.circle";case .queue:return "list.bullet";case .tracker:return "waveform";case .radio:return "radio"}}
}
@MainActor struct AboutView:View {
    @ObservedObject var model:AppModel
    var body:some View {
        VStack(alignment:.leading,spacing:18) {
            HStack{Text("GremlinAmp для Mac").font(.title2).fontWeight(.semibold);Spacer();Button("Закрыть"){model.showAbout=false}}
            Text("Музыка Аркадного зала Школы Империи. Автор проекта — Дмитрий Малыхин.").foregroundColor(.secondary)
            Picker("Оформление",selection:$model.theme){Text("Как в системе").tag("system");Text("Тёмный терминал").tag("dark");Text("Светлый терминал").tag("light")}.pickerStyle(.segmented)
            HStack {Link("Аркадный зал",destination:URL(string:"https://games.oopssre.ru/")!);Link("Медиатека",destination:URL(string:"https://games.oopssre.ru/gremlin.html")!);Link("Лаборатория",destination:URL(string:"https://games.oopssre.ru/lab/")!)}
            HStack {Link("Инженер школы Империи",destination:URL(string:"https://oopssre.ru/")!);Link("Telegram",destination:URL(string:"https://t.me/oopsSRE")!);Link("GitHub проекта",destination:URL(string:"https://github.com/GremlinEngineering/gremlinamp")!)}
            HStack {Button("Лицензии"){if let u=Bundle.main.url(forResource:"THIRD_PARTY_NOTICES",withExtension:"txt"){NSWorkspace.shared.open(u)}}}
            Text("Код приложения — MIT. Game Music Emu — LGPL-2.1-or-later. Музыка и постеры сохраняют права соответствующих авторов и правообладателей.").font(.caption).foregroundColor(.secondary)
        }.padding(26).frame(width:610)
    }
}

@MainActor struct PosterZoom:View {
    let document:PosterDocument
    @Environment(\.dismiss) private var dismiss
    @State private var scale:CGFloat=1
    @State private var offset=CGSize.zero
    @GestureState private var magnification:CGFloat=1
    @GestureState private var translation=CGSize.zero
    var body:some View {
        VStack(spacing:0) {
            HStack{Text("Постер").font(.headline);Spacer();Button("−"){scale=max(1,scale/1.4)};Button("+"){scale=min(10,scale*1.4)};Button("Сбросить"){reset()};Button("Закрыть"){dismiss()}}.padding(14)
            GeometryReader{g in
                ZStack {Color.black
                    if let image=NSImage(contentsOf:document.url){Image(nsImage:image).resizable().aspectRatio(contentMode:.fit).frame(width:g.size.width,height:g.size.height).scaleEffect(scale*magnification).offset(x:offset.width+translation.width,y:offset.height+translation.height)
                        .gesture(MagnificationGesture().updating($magnification){value,state,_ in state=value}.onEnded{value in scale=min(10,max(1,scale*value))})
                        .simultaneousGesture(DragGesture().updating($translation){value,state,_ in state=value.translation}.onEnded{value in offset.width+=value.translation.width;offset.height+=value.translation.height})
                        .onTapGesture(count:2){reset()}}
                }.clipped()
            }
        }.frame(width:850,height:680)
    }
    private func reset(){scale=1;offset = .zero}
}
