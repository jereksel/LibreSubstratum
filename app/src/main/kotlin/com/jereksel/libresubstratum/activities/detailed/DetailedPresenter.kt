package com.jereksel.libresubstratum.activities.detailed

import android.util.Log
import com.github.kittinunf.result.Result
import com.jereksel.libresubstratum.activities.detailed.DetailedContract.Presenter
import com.jereksel.libresubstratum.activities.detailed.DetailedContract.View
import com.jereksel.libresubstratum.adapters.ThemePackAdapterView
import com.jereksel.libresubstratum.data.Type1ExtensionToString
import com.jereksel.libresubstratum.data.Type2ExtensionToString
import com.jereksel.libresubstratum.domain.*
import com.jereksel.libresubstratum.domain.usecases.ICompileThemeUseCase
import com.jereksel.libresubstratum.extensions.getFile
import com.jereksel.libresubstratum.extensions.getLogger
import com.jereksel.libresubstratum.utils.ThemeNameUtils
import com.jereksel.libresubstratumlib.ThemePack
import com.jereksel.libresubstratumlib.ThemeToCompile
import com.jereksel.libresubstratumlib.Type1DataToCompile
import com.jereksel.libresubstratumlib.Type3Extension
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.rxkotlin.toObservable
import io.reactivex.rxkotlin.toSingle
import io.reactivex.schedulers.Schedulers
import java.io.File
import java.util.concurrent.Future

class DetailedPresenter(
        val packageManager: IPackageManager,
        val themeReader: IThemeReader,
        val overlayService: OverlayService,
        val activityProxy: IActivityProxy,
        val themeCompiler: ThemeCompiler,
        val themeExtractor: ThemeExtractor,
        val compileThemeUseCase: ICompileThemeUseCase
) : Presenter {

    var detailedView: View? = null
    lateinit var themePackState: List<ThemePackAdapterState>
    lateinit var themePack: ThemePack
    lateinit var appId: String
    var future: Future<File>? = null
    val log = getLogger()

    private var type3: Type3Extension? = null

    var init = false

    override fun setView(view: DetailedContract.View) {
        detailedView = view
    }

    override fun removeView() {
        future?.cancel(true)
        detailedView = null
    }

    override fun readTheme(appId: String) {

        val extractLocation = File(packageManager.getCacheFolder(), appId)

        if (!extractLocation.exists()) {
            extractLocation.mkdirs()
        }

        val apkLocation = packageManager.getAppLocation(appId)

        val future = this.future

        if (future == null || future.isCancelled) {
            this.future = themeExtractor.extract(apkLocation, extractLocation)
        }

        if (init) {
            detailedView?.addThemes(themePack)
            return
        }
        init = true

        this.appId = appId

        val location = packageManager.getAppLocation(appId)

//        val location = File(File(packageManager.getCacheFolder(), appId), "assets")

        Observable.fromCallable { themeReader.readThemePack(location) }
                .observeOn(Schedulers.computation())
                .subscribeOn(Schedulers.computation())
                .map {
                    //Remove apps that are not installed
                    val installedApps = it.themes.filter { packageManager.isPackageInstalled(it.application) }
                    ThemePack(installedApps, it.type3)
                }
                .map {
                    themePack = ThemePack(it.themes.sortedBy { packageManager.getAppName(it.application) }, it.type3)
                    themePackState = themePack.themes.map { ThemePackAdapterState() }
                    it
                }
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe { detailedView?.addThemes(it) }
    }

    override fun getNumberOfThemes() = themePack.themes.size

    //So we won't be stuck in refresh loop
    val seq = mutableSetOf<Int>()

    override fun setAdapterView(position: Int, view: ThemePackAdapterView) {

        if (compiling) {
            return
        }

        val theme = themePack.themes[position]
        val state = themePackState[position]

        view.reset()

//        if (!seq.contains(position)) {

        view.setAppIcon(packageManager.getAppIcon(theme.application))
        view.setAppName(packageManager.getAppName(theme.application))
        view.setAppId(theme.application)
        view.setCheckbox(state.checked)

        view.type1aSpinner((theme.type1.find { it.suffix == "a" }?.extension ?: listOf()).map(::Type1ExtensionToString), state.type1a)
        view.type1bSpinner((theme.type1.find { it.suffix == "b" }?.extension ?: listOf()).map(::Type1ExtensionToString), state.type1b)
        view.type1cSpinner((theme.type1.find { it.suffix == "c" }?.extension ?: listOf()).map(::Type1ExtensionToString), state.type1c)
        view.type2Spinner((theme.type2?.extensions ?: listOf()).map(::Type2ExtensionToString), state.type2)

//        } else {
//            seq.remove(position)
//        }

        val overlay = getOverlayIdForTheme(position)
        val isOverlayInstalled = packageManager.isPackageInstalled(overlay)

        if (isOverlayInstalled) {
            val overlayInfo = packageManager.getAppVersion(overlay)
            val themeInfo = packageManager.getAppVersion(appId)

            if (overlayInfo.first != themeInfo.first) {
                //Overlay can be updated
                view.setInstalled(overlayInfo.second, themeInfo.second)
            } else {
                view.setInstalled(null, null)
            }

            view.setEnabled(overlayService.getOverlayInfo(overlay).enabled)
        }

        view.setCompiling(state.compiling)
    }

    //TODO: Change name
    fun areVersionsTheSame(app1: String, app2: String): Boolean {

        Log.d("areVersionTheSame", "$app1 vs $app2")

        val app1Info = packageManager.getAppVersion(app1)
        val app2Info = packageManager.getAppVersion(app2)

        return app1Info.first == app2Info.first
    }

    fun getOverlayIdForTheme(position: Int): String {


        val theme = themePack.themes[position]
        val state = themePackState[position]

        val destAppId = theme.application

        val themeName = packageManager.getAppName(appId)

        val type1a = theme.type1.find {it.suffix == "a"}?.extension?.getOrNull(state.type1a)
        val type1b = theme.type1.find {it.suffix == "b"}?.extension?.getOrNull(state.type1b)
        val type1c = theme.type1.find {it.suffix == "c"}?.extension?.getOrNull(state.type1c)
        val type2 = theme.type2?.extensions?.getOrNull(state.type2)
        val type3 = type3

        return ThemeNameUtils.getTargetOverlayName(
                destAppId,
                themeName,
                type1a,
                type1b,
                type1c,
                type2,
                type3
        )
    }

    override fun getAppName(appId: String) = packageManager.getAppName(appId)

    override fun setCheckbox(position: Int, checked: Boolean) {
        themePackState[position].checked = checked
    }

    override fun setType1a(position: Int, spinnerPosition: Int) {
        themePackState[position].type1a = spinnerPosition
        seq.add(position)
        detailedView?.refreshHolder(position)
    }

    override fun setType1b(position: Int, spinnerPosition: Int) {
        themePackState[position].type1b = spinnerPosition
        seq.add(position)
        detailedView?.refreshHolder(position)
    }

    override fun setType1c(position: Int, spinnerPosition: Int) {
        themePackState[position].type1c = spinnerPosition
        seq.add(position)
        detailedView?.refreshHolder(position)
    }

    override fun setType2(position: Int, spinnerPosition: Int) {
        themePackState[position].type2 = spinnerPosition
        seq.add(position)
        detailedView?.refreshHolder(position)
    }

    override fun setType3(type3Extension: Type3Extension) {
        type3 = type3Extension
    }

    override fun openInSplit(adapterPosition: Int) {
        val app = themePack.themes[adapterPosition].application
        if (!activityProxy.openActivityInSplit(app)) {
            detailedView?.showToast("App couldn't be opened")
        }
    }

    var compiling = false

    override fun compileRunSelected() {
//        compileSelected1(false)

        val themesToCompile = themePackState.mapIndexed { index, themePackAdapterState -> index to themePackAdapterState }
                .filter { it.second.checked }

        detailedView?.showCompileDialog(themesToCompile.size)

        compilePositions(themesToCompile.map { it.first }) {
            detailedView?.increaseDialogProgress()
        }
                .doOnCompleted {
                    detailedView?.hideCompileDialog()
                }
                .subscribe()


    }

    override fun compileRunActivateSelected() {
//        compileSelected1(true)

        val themesToCompile = themePackState.mapIndexed { index, themePackAdapterState -> index to themePackAdapterState }
                .filter { it.second.checked }

        detailedView?.showCompileDialog(themesToCompile.size)

        compilePositions(themesToCompile.map { it.first }) { position ->
            val overlayId = getOverlayIdForTheme(position)
            if (packageManager.isPackageInstalled(overlayId)) {
                activateExclusive(position)
            }
            detailedView?.increaseDialogProgress()
        }
                .doOnCompleted {
                    detailedView?.hideCompileDialog()
                }
                .subscribe()
    }

    fun compileSelected1(activate: Boolean) {

        compiling = true

        val themesToCompile = themePackState.mapIndexed { index, themePackAdapterState -> index to themePackAdapterState }
                .filter { it.second.checked }

        detailedView?.showCompileDialog(themesToCompile.size)

        themesToCompile.toObservable()
                .observeOn(Schedulers.computation())
                .flatMap { Observable.just(it)
                        .subscribeOn(Schedulers.computation())
                        .observeOn(Schedulers.computation())
                        .map {
                            Log.d("Compiling on ", Thread.currentThread().toString())
                            compileForPosition(it.first)
                        }}
                .observeOn(Schedulers.io())
                .map {
                    Log.d("Installing on ", Thread.currentThread().toString())
                    overlayService.installApk(it)
                    detailedView?.increaseDialogProgress()
                    it.delete()
                }
                .toList()
                .observeOn(AndroidSchedulers.mainThread())
                .doOnSuccess {
                    overlayService.enableOverlays(themesToCompile.map { getOverlayIdForTheme(it.first) })
                    compiling = false
                    detailedView?.hideCompileDialog()
                }.subscribe()
    }
/*


        Observable.from(themePackState.mapIndexed { index, themePackAdapterState -> index to themePackAdapterState.copy() })
                .observeOn(Schedulers.from(THREAD_POOL_EXECUTOR))
                .subscribeOn(Schedulers.from(THREAD_POOL_EXECUTOR))
                .filter { it.second.checked }
                .map {
                    compileAndRun1(it.first)
                }
                .toList()
                .toSingle()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    detailedView?.hideCompileDialog()
                }
*/

    override fun compileAndRun(adapterPosition: Int) {

        val theme = themePack.themes[adapterPosition]
        val state = themePackState[adapterPosition]

//        val overlay = getOverlayIdForTheme(adapterPosition)
//
//        if (packageManager.isPackageInstalled(overlay) && areVersionsTheSame(overlay, appId)) {
//            toggle(adapterPosition)
//            detailedView?.refreshHolder(adapterPosition)
//            return
//        }

//        state.compiling = true
//
//        seq.add(adapterPosition)
//        detailedView?.refreshHolder(adapterPosition)
//
        compilePositions(listOf(adapterPosition)) { position ->
            val overlayId = getOverlayIdForTheme(position)
            if (packageManager.isPackageInstalled(overlayId)) {
                toggle(adapterPosition)
            }
        }
                .map { it.first }
                .filter { it.component2() != null }
                .map { it.component2()!! }
//                .map { it.message }
                .toList()
                .subscribe {
                    log.debug("Error: {}", it)
                }
//                .subscribe()

/*
        Observable.just(adapterPosition)
                .subscribeOn(Schedulers.computation())
                .observeOn(Schedulers.computation())
                .flatMap {
                    compileForPositionObservable(it)
                            .subscribeOn(Schedulers.computation())
                            .observeOn(Schedulers.computation())
                            .zipWith(listOf(it), {a,b -> Pair(a,b)})
                }
                .map {
                    overlayService.installApk(it.first)
                    toggle(it.second)
                    it
                }
                .observeOn(AndroidSchedulers.mainThread())
                .forEach {
                    state.compiling = false
                    detailedView?.refreshHolder(it.second)
                }*/
    }

    override fun selectAll() {
        themePackState.forEachIndexed { index, state ->
            if (!state.checked) {
                detailedView?.refreshHolder(index)
                state.checked = true
            }
        }
    }

    override fun deselectAll() {
        themePackState.forEachIndexed { index, state ->
            if (state.checked) {
                detailedView?.refreshHolder(index)
                state.checked = false
            }
        }
    }

    private fun compilePositions(positions: List<Int>, afterInstalling: (Int) -> Unit): Observable<Pair<Result<File, Exception>, Int>> {

        return Observable.from(positions.toList())
                .subscribeOn(Schedulers.computation())
                .observeOn(Schedulers.computation())
                .filter {
                    val overlay = getOverlayIdForTheme(it)

                    if (packageManager.isPackageInstalled(overlay) && areVersionsTheSame(overlay, appId)) {
                        afterInstalling(it)
                        detailedView?.refreshHolder(it)
                        false
                    } else {
                        true
                    }
                }
                .flatMap {
                    val adapterPosition = it
                    val state = themePackState[adapterPosition]
                    state.compiling = true
                    detailedView?.refreshHolder(adapterPosition)

                    compileForPositionObservable(it)
                            .subscribeOn(Schedulers.computation())
                            .observeOn(Schedulers.computation())
                            .zipWith(listOf(it), { a,b -> Pair(Result.of { a },b) })
                            .onErrorReturn { t ->
                                log.warn("Overlay compilation failed", t)
                                Pair(Result.error(t as Exception), adapterPosition)
                            }
                }
                .map {

                    val file = it.first.component1()

                    if (file != null) {

                        overlayService.installApk(file)
                        val overlay = getOverlayIdForTheme(it.second)

                        //Replacing substratum theme (the keys are different and overlay can't be just replaced)
                        if (packageManager.isPackageInstalled(overlay) && !areVersionsTheSame(overlay, appId)) {
                            overlayService.uninstallApk(overlay)
                            overlayService.installApk(file)
                        }

                        file.delete()

                    }

                    afterInstalling(it.second)
                    it
                }
                .observeOn(AndroidSchedulers.mainThread())
                .map {
                    val adapterPosition = it.second
                    val state = themePackState[adapterPosition]
                    state.compiling = false
                    detailedView?.refreshHolder(adapterPosition)
                    it
                }

    }

    fun activateExclusive(position: Int) {
        val theme = themePack.themes[position]
        val overlay = getOverlayIdForTheme(position)
        val info = overlayService.getOverlayInfo(overlay)
        if (!info.enabled) {
            val overlays = overlayService.getAllOverlaysForApk(theme.application).filter { it.enabled }
            overlayService.disableOverlays(overlays.map { it.overlayId })
            overlayService.toggleOverlay(overlay, !info.enabled)
        }
    }

    fun toggle(position: Int) {
        val theme = themePack.themes[position]
        val overlay = getOverlayIdForTheme(position)
        val info = overlayService.getOverlayInfo(overlay)
        if (!info.enabled) {
            val overlays = overlayService.getAllOverlaysForApk(theme.application).filter { it.enabled }
            overlayService.disableOverlays(overlays.map { it.overlayId })
            overlayService.enableOverlay(overlay)
        } else {
            overlayService.disableOverlay(overlay)
        }
    }

    fun compileForPositionObservable(position: Int): Observable<File> {

        val cacheLocation = future!!.get()

        val overlay = getOverlayIdForTheme(position)
        val state = themePackState[position]
        val theme = themePack.themes[position]

        val location = getFile(cacheLocation, "assets", "overlays", theme.application)

        val type1a = theme.type1.find {it.suffix == "a"}?.extension?.getOrNull(state.type1a)
        val type1b = theme.type1.find {it.suffix == "b"}?.extension?.getOrNull(state.type1b)
        val type1c = theme.type1.find {it.suffix == "c"}?.extension?.getOrNull(state.type1c)

        val type1 = listOf(type1a, type1b, type1c).zip(listOf("a", "b", "c"))
                .filter { it.first != null }
                .map {
                    Type1DataToCompile(it.first!!, it.second)
                }

        val type2 = theme.type2?.extensions?.getOrNull(state.type2)

        val themeInfo = packageManager.getAppVersion(appId)

        val fixedTargetApp = if (theme.application.startsWith("com.android.systemui.")) "com.android.systemui" else theme.application

        return compileThemeUseCase.execute(
                theme,
                appId,
                location,
                theme.application,
                type1a?.name,
                type1b?.name,
                type1c?.name,
                type2?.name,
                type3?.name
        )

//        val themeToCompile = ThemeToCompile(overlay, appId, fixedTargetApp, type1, type2,
//                type3, themeInfo.first, themeInfo.second)
//
//        return themeCompiler.compileTheme(themeToCompile, location)
    }

    fun compileForPosition(position: Int): File {

        val cacheLocation = future!!.get()

        val overlay = getOverlayIdForTheme(position)
        val state = themePackState[position]
        val theme = themePack.themes[position]

        val location = getFile(cacheLocation, "assets", "overlays", theme.application)

        val type1a = theme.type1.find {it.suffix == "a"}?.extension?.getOrNull(state.type1a)
        val type1b = theme.type1.find {it.suffix == "b"}?.extension?.getOrNull(state.type1b)
        val type1c = theme.type1.find {it.suffix == "c"}?.extension?.getOrNull(state.type1c)

        val type1 = listOf(type1a, type1b, type1c).zip(listOf("a", "b", "c"))
                .filter { it.first != null }
                .map {
                    Type1DataToCompile(it.first!!, it.second)
                }
        val type2 = theme.type2?.extensions?.getOrNull(state.type2)

        val themeInfo = packageManager.getAppVersion(appId)

        val fixedTargetApp = if (theme.application.startsWith("com.android.systemui.")) "com.android.systemui" else theme.application

        val themeToCompile = ThemeToCompile(overlay, appId, fixedTargetApp, type1, type2,
                type3, themeInfo.first, themeInfo.second)

        return themeCompiler.compileTheme(themeToCompile, location)
    }

    fun compileAndRun1(position: Int, activate: Boolean = true) {
        val overlay = getOverlayIdForTheme(position)
        val state = themePackState[position]
        val theme = themePack.themes[position]
        if (packageManager.isPackageInstalled(overlay) && areVersionsTheSame(overlay, appId)) {
            activateExclusive(position)
        } else {

            val apk = compileForPosition(position)
//            val overlays = overlayService.getAllOverlaysForApk(theme.application).filter { it.enabled }
            overlayService.installApk(apk)

            //Replacing substratum theme (the keys are different and overlay can't be just replaced)
            if (packageManager.isPackageInstalled(overlay) && !areVersionsTheSame(overlay, appId)) {
                overlayService.uninstallApk(overlay)
                overlayService.installApk(apk)
            }

            apk.delete()

            if (activate) {
                activateExclusive(position)
            }
//
//            if (activate) {
//                overlayService.disableOverlays(overlays.map { it.overlayId })
//                overlayService.enableOverlay(overlay)
//            }
        }

        state.compiling = false
        seq.add(position)
        detailedView?.refreshHolder(position)
    }

    data class ThemePackAdapterState(
            var checked: Boolean = false,
            var compiling: Boolean = false,
            var type1a: Int = 0,
            var type1b: Int = 0,
            var type1c: Int = 0,
            var type2: Int = 0
    )

}
