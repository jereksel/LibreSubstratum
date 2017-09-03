package com.jereksel.libresubstratum.activities.installed

import android.os.AsyncTask
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.jereksel.libresubstratum.activities.installed.InstalledContract.Presenter
import com.jereksel.libresubstratum.activities.installed.InstalledContract.View
import com.jereksel.libresubstratum.data.InstalledOverlay
import com.jereksel.libresubstratum.domain.IActivityProxy
import com.jereksel.libresubstratum.domain.IPackageManager
import com.jereksel.libresubstratum.domain.OverlayService
import rx.Observable
import rx.Subscription
import rx.android.schedulers.AndroidSchedulers
import rx.lang.kotlin.toSingletonObservable
import rx.schedulers.Schedulers
import java.lang.ref.WeakReference

class InstalledPresenter(
        val packageManager: IPackageManager,
        val overlayService: OverlayService,
        val activityProxy: IActivityProxy
) : Presenter {

    private var view = WeakReference<View>(null)
    private var subscription: Subscription? = null
    private var overlays: List<InstalledOverlay>? = null

    @JvmField
    var state: Array<Boolean>? = null

    override fun setView(view: View) {
        this.view = WeakReference(view)
    }

    override fun getInstalledOverlays() {

        val o = overlays

        if (o != null) {
            view.get()?.addOverlays(o)
        }

        subscription = Observable.fromCallable { packageManager.getInstalledOverlays() }
                .subscribeOn(Schedulers.computation())
                .observeOn(Schedulers.computation())
                .map {
                    it.sortedWith(compareBy({ it.targetName }, { it.sourceThemeName }, { it.type1a },
                            { it.type1b }, { it.type1c }, { it.type2 }, { it.type3 }))
                }
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    overlays = it
                    state = it.map { false }.toTypedArray()
                    view.get()?.addOverlays(it)
                }
    }

    override fun toggleOverlay(overlayId: String, enabled: Boolean) {
        overlayService.toggleOverlay(overlayId, enabled)
        if (overlayId.startsWith("com.android.systemui")) {
            view.get()?.showSnackBar("This change requires SystemUI restart", "Restart SystemUI", { overlayService.restartSystemUI() })
        }
    }

    private fun selectedOverlays() = (overlays ?: emptyList()).filterIndexed { index, _ -> state!![index] }

    override fun uninstallSelected() {

        val toUninstall = selectedOverlays()
                .map { it.overlayId }

        toUninstall.toSingletonObservable()
                .observeOn(Schedulers.computation())
                .subscribeOn(Schedulers.computation())
                .map { overlayService.uninstallApk(it) }
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe()

    }

    override fun enableSelected() {


        val toUninstall = selectedOverlays()
                .map { it.overlayId }

        toUninstall.toSingletonObservable()
                .observeOn(Schedulers.computation())
                .subscribeOn(Schedulers.computation())
                .map { overlayService.enableOverlays(it) }
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    view.get()?.refreshRecyclerView()
                }

    }

    override fun disableSelected() {


        val toUninstall = selectedOverlays()
                .map { it.overlayId }

        toUninstall.toSingletonObservable()
                .observeOn(Schedulers.computation())
                .subscribeOn(Schedulers.computation())
                .map { overlayService.disableOverlays(it) }
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    view.get()?.refreshRecyclerView()
                }

    }

    override fun getOverlayInfo(overlayId: String) = overlayService.getOverlayInfo(overlayId)

    override fun openActivity(appId: String) = activityProxy.openActivityInSplit(appId)

    override fun uninstallAll() {

        val o = overlays
        overlays = null

        view.get()?.hideRecyclerView()
        Observable.from(o ?: emptyList())
                .observeOn(Schedulers.computation())
                .subscribeOn(Schedulers.computation())
                .distinct()
                .toList()
                .map {
                    Log.d("InstalledPresenter", "Uninstalling ${it.map { it.overlayId }}")
                    overlayService.uninstallApk(it.map { it.overlayId })
                    it
                }
                .observeOn(AndroidSchedulers.mainThread())
                .doOnCompleted {
                    view.get()?.showRecyclerView()
                }
                .subscribe()
    }

    override fun getState(position: Int) = state!![position]

    override fun setState(position: Int, isEnabled: Boolean) {
        state!![position] = isEnabled
    }

    override fun removeView() = Unit

}