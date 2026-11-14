import Foundation
import AppKit
import Combine

@MainActor final class AppModel:ObservableObject {
    let library=LibraryStore()
    let player:Playback
    let startup=StartupSettings()
    let clientUpdates=ClientUpdates()
    @Published var section:LibrarySection = .games
    @Published var games:[Game]=[]
    @Published var series:[MusicSeries]=[]
    @Published var tracks:[Track]=[]
    @Published var complete=Set<String>()
    @Published var downloadStates=[String:CollectionState]()
    @Published var selectedGame:Game?
    @Published var selectedSeries:MusicSeries?
    @Published var search=""
    @Published var letter=""
    @Published var platform=UserDefaults.standard.string(forKey:"platform") ?? "" {didSet{UserDefaults.standard.set(platform,forKey:"platform")}}
    @Published var year=UserDefaults.standard.string(forKey:"year") ?? "" {didSet{UserDefaults.standard.set(year,forKey:"year")}}
    @Published var shortSeconds=UserDefaults.standard.integer(forKey:"shortSeconds") {didSet{UserDefaults.standard.set(shortSeconds,forKey:"shortSeconds")}}
    @Published var unknown=UserDefaults.standard.string(forKey:"unknownDuration") ?? "show" {didSet{UserDefaults.standard.set(unknown,forKey:"unknownDuration")}}
    @Published var theme=UserDefaults.standard.string(forKey:"theme") ?? "system" {didSet{UserDefaults.standard.set(theme,forKey:"theme")}}
    @Published var status="Загрузка каталога…"
    @Published var busy=false
    @Published var downloadStatus=""
    @Published var downloading=false
    @Published var showAbout=false
    @Published var poster:PosterDocument?
    @Published var showSettings=false
    @Published var settingsTab=0
    @Published var planningDownload=false
    @Published var indexStatus="Индекс похожей музыки ещё не загружен"
    @Published var updatingIndex=false
    @Published var similarityReasons=["rhythm":"Индекс анализа ещё не загружен","sound":"Индекс анализа ещё не загружен"]
    @Published var continuation=ContinuationMode(rawValue:UserDefaults.standard.string(forKey:"continuation") ?? "stop") ?? .stop {
        didSet { UserDefaults.standard.set(continuation.rawValue,forKey:"continuation");collectionGeneration+=1 }
    }
    @Published var continueLocal=UserDefaults.standard.object(forKey:"continueLocal") as? Bool ?? true {
        didSet { UserDefaults.standard.set(continueLocal,forKey:"continueLocal");collectionGeneration+=1 }
    }
    @Published var autoMusic=UserDefaults.standard.bool(forKey:"autoMusic") {
        didSet { UserDefaults.standard.set(autoMusic,forKey:"autoMusic");if autoMusic {serviceTick()} }
    }
    @Published var musicInterval=SyncInterval(rawValue:UserDefaults.standard.string(forKey:"musicInterval") ?? "morning") ?? .morning {
        didSet { UserDefaults.standard.set(musicInterval.rawValue,forKey:"musicInterval");serviceTick() }
    }
    @Published var followAll=UserDefaults.standard.bool(forKey:"followAll") {
        didSet { UserDefaults.standard.set(followAll,forKey:"followAll") }
    }
    @Published var followedSeries=UserDefaults.standard.stringArray(forKey:"followedSeries") ?? [] {
        didSet { UserDefaults.standard.set(followedSeries,forKey:"followedSeries") }
    }
    @Published var allowExpensiveNetwork=UserDefaults.standard.bool(forKey:"allowExpensiveNetwork") {
        didSet { UserDefaults.standard.set(allowExpensiveNetwork,forKey:"allowExpensiveNetwork") }
    }
    @Published var musicSyncStatus="Автообновление музыки ещё не выполнялось"
    @Published var musicLastSuccess=UserDefaults.standard.object(forKey:"musicLastSuccess") as? Date
    @Published var syncingMusic=false
    private var browsingGeneration=0
    private var collectionGeneration=0
    private var downloadTask:Task<Void,Never>?
    private var planningTask:Task<Void,Never>?
    private var cancelledDownload=false
    private var serviceTimer:Timer?
    private var musicLastAttempt=UserDefaults.standard.object(forKey:"musicLastAttempt") as? Date
    private var hasCatalog=false
    init() {
        player=Playback(library:library)
        player.queueFinished={ [weak self] track in self?.continueQueue(from:track) }
        player.trackStarted={ [weak self] in self?.refreshSimilarityAvailability() }
        serviceTimer=Timer.scheduledTimer(withTimeInterval:60,repeats:true) { [weak self] _ in
            Task { @MainActor in self?.serviceTick() }
        }
        refreshSimilarityAvailability()
    }
    deinit {serviceTimer?.invalidate()}
    func load() {
        guard !busy else {return};busy=true
        Task {
            complete=await library.completeIDs()
            do {
                let c=try await library.catalog();await apply(c)
                hasCatalog=true;status="\(games.count) игр · \(c.offline ? "сохранённый каталог" : "каталог обновлён")"
            } catch {status="Каталог недоступен: \(error.localizedDescription). Повторите обновление каталога."}
            busy=false;refreshIndex();serviceTick()
        }
    }
    private func apply(_ catalog:Catalog) async {
        var seen=Set(catalog.games.map(\.id))
        let saved=await library.savedGames().filter{seen.insert($0.id).inserted}
        games=catalog.games+saved;series=catalog.series
        if let selected=selectedSeries {selectedSeries=series.first{$0.id==selected.id}}
        if let selected=selectedGame,let latest=games.first(where:{$0.id==selected.id}),latest != selected {
            let token=browsingGeneration;selectedGame=latest;tracks=[]
            let fresh=try? await library.tracks(latest)
            if browsingGeneration==token,selectedGame?.id==latest.id {tracks=fresh ?? []}
        }
        await refreshDownloadStates()
    }
    func refreshDownloadStates() async {
        complete=await library.completeIDs()
        downloadStates=await library.states(games)
    }
    func navigate(_ s:LibrarySection) {browsingGeneration+=1;section=s;selectedGame=nil;selectedSeries=nil;tracks=[];search="";letter=""}
    func matches(_ game:Game) -> Bool {(platform.isEmpty || game.platform==platform) && (year.isEmpty || (game.year.map{String($0)} ?? "Не указан") == year)}
    func matchesTitle(_ title:String) -> Bool {(search.isEmpty || title.localizedCaseInsensitiveContains(search)) && (letter.isEmpty || initial(title)==letter)}
    func initial(_ title:String) -> String {guard let c=title.first else {return "#"};if c.isNumber{return "0–9"};return c.isLetter ? String(c).uppercased() : "#"}
    func members(_ s:MusicSeries) -> [Game] {var seen=Set<String>();let byID=Dictionary(games.map{($0.id,$0)},uniquingKeysWith:{a,_ in a});return s.members.compactMap{byID[$0]}.filter{seen.insert($0.id).inserted && matches($0)}}
    var visibleGames:[Game] {
        let source=selectedSeries.map(members) ?? games
        return source.filter{matches($0) && matchesTitle($0.title) && (section != .downloaded || complete.contains($0.id))}
    }
    var visibleSeries:[MusicSeries] {series.filter{!members($0).isEmpty && matchesTitle($0.title)}.sorted{$0.title.localizedStandardCompare($1.title) == .orderedAscending}}
    func includes(_ t:Track) -> Bool {PlaybackRules.include(t.knownMS,threshold:shortSeconds,unknown:unknown)}
    var visibleTracks:[Track] {tracks.filter{matchesTitle($0.title) && includes($0)}}
    var title:String {selectedGame?.title ?? selectedSeries?.title ?? section.rawValue}
    var selectionDownloadTitle:String {
        let list:[Game]
        if let g=selectedGame {list=[g]}
        else if section == .series {list=visibleSeries.flatMap(members)}
        else {list=visibleGames}
        guard !list.isEmpty else {return "Скачать"}
        let states=list.map{downloadStates[$0.id] ?? .download}
        if states.allSatisfy({$0 == .downloaded}) {return "Скачано"}
        if states.allSatisfy({$0 == .downloaded || $0 == .save}) {return "Сохранить"}
        if states.contains(.updates) {return "Есть обновления"}
        return states.contains(.partial) ? "Докачать" : "Скачать"
    }
    var platforms:[String] {Array(Set(games.map(\.platform))).filter{!$0.isEmpty}.sorted()}
    var years:[String] {Array(Set(games.map{$0.year.map{String($0)} ?? "Не указан"})).sorted()}
    var letters:[String] {
        let titles:[String]
        if selectedGame != nil {titles=tracks.map(\.title)}
        else if section == .series {titles=series.filter{!members($0).isEmpty}.map(\.title)}
        else {titles=(selectedSeries.map(members) ?? games).filter{matches($0)}.map(\.title)}
        return Array(Set(titles.map(initial))).sorted()
    }
    func resetFilters() {search="";letter="";platform="";year="";shortSeconds=0;unknown="show"}
    func open(_ game:Game) {
        browsingGeneration+=1;let token=browsingGeneration;selectedGame=game;tracks=[];search="";letter="";status="Загрузка треклиста…"
        Task {do{let list=try await library.tracks(game);guard token==browsingGeneration else {return};tracks=list;downloadStates[game.id]=await library.state(game);status="\(list.count) композиций"}catch{guard token==browsingGeneration else{return};status=error.localizedDescription}}
    }
    func open(_ s:MusicSeries) {browsingGeneration+=1;selectedSeries=s;selectedGame=nil;section = .games;search="";letter=""}
    func back() {browsingGeneration+=1;selectedGame=nil;tracks=[];search="";letter="";if selectedSeries != nil {return};if section != .downloaded {section = .games}}
    func playVisible(append:Bool=false) {
        if selectedGame != nil {let ts=visibleTracks;if append{player.append(ts)}else{player.play(ts)};return}
        if section == .series {var ids=Set<String>();playGames(visibleSeries.flatMap(members).filter{ids.insert($0.id).inserted},append:append);return}
        playGames(visibleGames,append:append)
    }
    func playGames(_ list:[Game],append:Bool=false) {
        collectionGeneration+=1;let token=collectionGeneration;let intent=player.userRevision;status="Подготовка очереди…"
        Task {do{var q:[Track]=[];for g in list {let ts=try await library.tracks(g);q += ts.filter(includes)};guard token==collectionGeneration,player.userRevision==intent else{return};if q.isEmpty{status="Нет подходящих композиций"}else{if append{player.append(q)}else{player.play(q)};status="В очереди: \(q.count)"}}catch{if token==collectionGeneration{status=error.localizedDescription}}}
    }
    func playTrack(_ t:Track) {collectionGeneration+=1;let visible=visibleTracks;guard let i=visible.firstIndex(where:{$0.id==t.id}) else{return};player.play(visible,at:i)}
    func selectDownloads() {
        if let g=selectedGame {planDownload([g])}
        else if section == .series {var ids=Set<String>();planDownload(visibleSeries.flatMap(members).filter{ids.insert($0.id).inserted})}
        else {planDownload(visibleGames,seriesID:selectedSeries?.id)}
    }
    func planDownload(_ list:[Game],seriesID:String?=nil,wholeLibrary:Bool=false) {
        guard !list.isEmpty,!downloading,!planningDownload,!syncingMusic else {return}
        planningDownload=true;status="Расчёт размера…"
        planningTask=Task {do {
            defer {planningDownload=false;planningTask=nil}
            let plan=try await library.plan(list) { [weak self] position,count,title in
                self?.status="Расчёт размера · \(position)/\(count) · \(title)"
            }
            downloadStates.merge(plan.states,uniquingKeysWith:{_,new in new})
            let a=NSAlert();a.messageText=plan.bytes==0 ? "Сохранить коллекции?" : "Скачать музыку?"
            a.informativeText="Коллекций: \(list.count). Осталось скачать \(ByteCountFormatter.string(fromByteCount:plan.bytes,countStyle:.file)) музыки. Постеры загружаются отдельно. Файлы из кеша будут использованы повторно."
            let follow=NSButton(checkboxWithTitle:wholeLibrary ? "Следить за всей медиатекой, включая новые игры" : "Следить за новыми играми этой серии",target:nil,action:nil)
            let automatic=NSButton(checkboxWithTitle:"Автоматически обновлять сохранённую музыку",target:nil,action:nil)
            follow.state=(wholeLibrary ? followAll : seriesID.map{followedSeries.contains($0)} ?? false) ? .on : .off
            automatic.state=autoMusic ? .on : .off
            if wholeLibrary || seriesID != nil {
                let accessory=NSStackView(views:[follow,automatic]);accessory.orientation = .vertical;accessory.alignment = .leading;accessory.spacing=10
                accessory.frame=NSRect(x:0,y:0,width:410,height:60);a.accessoryView=accessory
            }
            a.addButton(withTitle:plan.bytes==0 ? "Сохранить" : "Скачать");a.addButton(withTitle:"Отмена")
            guard a.runModal() == .alertFirstButtonReturn else {status="Загрузка отменена";return}
            if wholeLibrary { followAll=follow.state == .on }
            if let id=seriesID {setFollowed(id,follow.state == .on)}
            if wholeLibrary || seriesID != nil {autoMusic=automatic.state == .on}
            download(list)
        }catch{planningDownload=false;planningTask=nil;status=error is CancellationError ? "Расчёт остановлен" : error.localizedDescription}}
    }
    func downloadAll() {
        guard !planningDownload,!downloading,!syncingMusic else {return};planningDownload=true;status="Загрузка полного каталога…"
        Task {
            do {
                let c=try await library.catalog(requireFresh:true);await apply(c)
                planningDownload=false;planDownload(c.games,wholeLibrary:true)
            } catch {planningDownload=false;status=error.localizedDescription}
        }
    }
    private func download(_ list:[Game],automatic:Bool=false) {
        guard !downloading else{return};downloading=true;cancelledDownload=false
        downloadTask=Task {
            do {
                for (gIndex,g) in list.enumerated() {
                    if cancelledDownload {break}
                    let ts=try await library.tracks(g)
                    for (i,t) in ts.enumerated() {
                        if cancelledDownload {break}
                        downloadStatus="\(gIndex+1)/\(list.count) · \(g.title) · файл \(i+1)/\(ts.count)"
                        let file=try await library.fetch(t)
                        if !cancelledDownload {_ = await library.measured(t,url:file)}
                    }
                    if cancelledDownload {break}
                    _=try? await library.artwork(id:g.id,series:false,preview:false)
                    _=try? await library.artwork(id:g.id,series:false,preview:true)
                    try await library.complete(g);complete=await library.completeIDs()
                    downloadStates[g.id]=await library.state(g)
                }
                if !cancelledDownload {
                    let selected=Set(list.map(\.id))
                    for s in series where s.members.contains(where:{selected.contains($0)}) {_ = try? await library.artwork(id:s.id,series:true,preview:false);_ = try? await library.artwork(id:s.id,series:true,preview:true)}
                }
                downloadStatus=cancelledDownload ? "Скачивание остановлено; готовые файлы сохранены" : "Скачивание завершено"
                if let g=selectedGame {tracks=(try? await library.tracks(g)) ?? tracks}
            }catch{downloadStatus="Загрузка: \(error.localizedDescription). Повтор продолжит скачивание."}
            if automatic {
                musicSyncStatus=downloadStatus
                if !cancelledDownload && !downloadStatus.hasPrefix("Загрузка:") {
                    musicLastSuccess=Date();UserDefaults.standard.set(musicLastSuccess,forKey:"musicLastSuccess")
                }
                syncingMusic=false
            }
            await refreshDownloadStates()
            status=downloadStatus
            downloading=false;downloadTask=nil
        }
    }
    func cancelDownload() {
        cancelledDownload=true;planningTask?.cancel()
        downloadStatus="Останавливаю после текущего файла…"
    }
    func showPoster(id:String,series:Bool=false) {
        Task {do{if let u=try await library.artwork(id:id,series:series,preview:false) {poster=PosterDocument(url:u)}else{status="Постер отсутствует"}}catch{status=error.localizedDescription}}
    }
    func openSettings(tab:Int=0) {settingsTab=tab;startup.refresh();showSettings=true;refreshSimilarityAvailability()}
    func setFollowed(_ id:String,_ value:Bool) {
        var selected=Set(followedSeries)
        if value {selected.insert(id)}else{selected.remove(id)}
        followedSeries=selected.sorted()
    }
    private func serviceTick() {
        guard hasCatalog,autoMusic,!busy,!planningDownload,!downloading,!syncingMusic,
              SyncSchedule.due(now:Date(),last:musicLastAttempt,interval:musicInterval) else {return}
        syncMusic()
    }
    func syncMusic() {
        guard !downloading,!planningDownload,!syncingMusic else {return}
        syncingMusic=true;cancelledDownload=false;musicSyncStatus="Проверка сохранённой музыки…"
        musicLastAttempt=Date();UserDefaults.standard.set(musicLastAttempt,forKey:"musicLastAttempt")
        Task {
            do {
                let c=try await library.catalog(requireFresh:true);await apply(c)
                var ids=await library.completeIDs()
                let selectedSeries=Set(followedSeries)
                for s in c.series where selectedSeries.contains(s.id) {ids.formUnion(s.members)}
                let targets=c.games.filter{followAll || ids.contains($0.id)}
                if targets.isEmpty {musicSyncStatus="Нет сохранённых коллекций или подписок";syncingMusic=false;return}
                for game in targets {
                    if cancelledDownload {break}
                    musicSyncStatus="Проверка треклиста · \(game.title)"
                    _=try await library.tracks(game,refresh:true)
                }
                if cancelledDownload {musicSyncStatus="Обновление остановлено";syncingMusic=false;return}
                refreshIndex();download(targets,automatic:true)
            } catch {musicSyncStatus="Обновление музыки: \(error.localizedDescription)";syncingMusic=false}
        }
    }
    func refreshIndex() {
        guard !updatingIndex else{return};updatingIndex=true
        Task {indexStatus=await library.refreshIndex();updatingIndex=false;refreshSimilarityAvailability()}
    }
    func refreshSimilarityAvailability() {
        let seed=player.current?.analysisKey
        Task {
            var reasons=[String:String]()
            for mode in [ContinuationMode.rhythm,.sound] {
                if let reason=await library.similarityReason(seed:player.current,mode:mode) {reasons[mode.rawValue]=reason}
            }
            guard player.current?.analysisKey==seed else {return};similarityReasons=reasons
        }
    }
    private func continueQueue(from seed:Track) {
        let mode=continuation
        guard mode != .stop else{return}
        collectionGeneration+=1;let token=collectionGeneration,intent=player.userRevision
        let local=continueLocal,filterPlatform=platform,filterYear=year,threshold=shortSeconds,unknownMode=unknown
        let all=games,groups=series,playedGames=player.playedGames,playedKeys=player.playedKeys
        player.state="Подбор продолжения…"
        Task {
            @MainActor func active() -> Bool {
                token==collectionGeneration && intent==player.userRevision && mode==continuation &&
                local==continueLocal && filterPlatform==platform && filterYear==year && threshold==shortSeconds &&
                unknownMode==unknown && !player.loop && !player.repeatOne
            }
            defer {
                if token==collectionGeneration && player.state=="Подбор продолжения…" {player.state="Очередь завершена"}
            }
            do {
                var next=[Track]()
                if mode.acoustic {
                    if let track=try await library.similarTrack(seed:seed,mode:mode,games:all,played:playedKeys,localOnly:local,
                                                               platform:filterPlatform,year:filterYear,threshold:threshold,unknown:unknownMode) {next=[track]}
                } else if let game=all.first(where:{$0.id==seed.gameID}) {
                    let candidates=ContinuationRules.candidates(seed:game,games:all,series:groups,mode:mode,
                        played:playedGames,platform:filterPlatform,year:filterYear)
                    for candidate in candidates {
                        guard active() else{return}
                        if local {
                            let state=await library.state(candidate)
                            if state != .downloaded && state != .save {continue}
                        }
                        let list=(try? await library.tracks(candidate,localOnly:local)) ?? []
                        next=list.filter{PlaybackRules.include($0.knownMS,threshold:threshold,unknown:unknownMode)}
                        if !next.isEmpty {break}
                    }
                }
                guard active() else{return}
                if next.isEmpty {player.state="Очередь завершена · подходящего продолжения нет"}
                else {player.continueWith(next)}
            } catch {if active(){player.state="Продолжение: \(error.localizedDescription)"}}
        }
    }
}
