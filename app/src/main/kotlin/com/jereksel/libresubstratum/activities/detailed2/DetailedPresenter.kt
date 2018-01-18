package com.jereksel.libresubstratum.activities.detailed2

import com.hannesdorfmann.mosby3.mvi.MviBasePresenter
import com.jereksel.libresubstratum.activities.detailed2.DetailedViewState.Companion.INITIAL
import com.jereksel.libresubstratum.domain.IPackageManager
import com.jereksel.libresubstratum.extensions.getLogger
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.functions.BiFunction
import io.reactivex.rxkotlin.zipWith
import io.reactivex.subjects.BehaviorSubject
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class DetailedPresenter @Inject constructor(
        val actionProcessor: DetailedActionProcessorHolder,
        val packageManager: IPackageManager
): MviBasePresenter<DetailedView, DetailedViewState>() {

    val log = getLogger()

    lateinit var appId: String

    override fun bindIntents() {

        actionProcessor.appId = appId

        val simpleProcessor = DetailedSimpleUIActionProcessor(appId, viewStateObservable)

        val s1 = intent(DetailedView::getSimpleUIActions)
                .compose(simpleProcessor.actionProcessor)

        val s2 = intent(DetailedView::getActions)
                .startWith(DetailedAction.InitialAction(appId))

        val s3 = actionProcessor.backflow

        val s4 = BehaviorSubject.create<DetailedAction>()

        val states = Observable.merge(s1, s2, s3, s4)
                .compose(actionProcessor.actionProcessor)
                .scan(INITIAL, { t1, t2 ->
                    val result = DetailedReducer.apply(t1, t2)
                    result.second.forEach { s4.onNext(it) }
                    result.first
                })
                .distinctUntilChanged()
                .observeOn(AndroidSchedulers.mainThread())

        subscribeViewState(states, DetailedView::render)

    }

    fun getAppIcon(appId: String) = packageManager.getAppIcon(appId)

}