package dev.rosty.core.contracts

import dev.rosty.core.model.headless.MmsNotificationAnswer
import dev.rosty.core.model.headless.MmsNotificationRequest
import dev.rosty.core.model.headless.MmsRetrievedAnswer
import dev.rosty.core.model.headless.MmsRetrievedRequest
import dev.rosty.core.model.headless.MmsSendReqAnswer
import dev.rosty.core.model.headless.MmsSendReqRequest
import dev.rosty.core.model.headless.MsgRemindAnswer
import dev.rosty.core.model.headless.MsgRemindRequest
import dev.rosty.core.model.headless.SmsClassifyAnswer
import dev.rosty.core.model.headless.SmsClassifyRequest
import dev.rosty.core.model.headless.TripAlarmAnswer
import dev.rosty.core.model.headless.TripAlarmRequest
import dev.rosty.core.model.headless.TripEndAnswer
import dev.rosty.core.model.headless.TripEndRequest
import dev.rosty.core.model.headless.TripFixAnswer
import dev.rosty.core.model.headless.TripFixRequest
import dev.rosty.core.model.headless.TripNoFixRequest
import dev.rosty.core.model.headless.WaDigestAnswer
import dev.rosty.core.model.headless.WaDigestRequest
import dev.rosty.core.model.headless.WaReplyAnswer
import dev.rosty.core.model.headless.WaReplyRequest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/** Fixture stem to model for the headless kinds (`headless.<kind>.*`). */
object HeadlessContracts : ContractModels {
    override val models: Map<String, KSerializer<*>> =
        buildMap {
            pair<SmsClassifyRequest, SmsClassifyAnswer>("sms_classify")
            pair<MmsNotificationRequest, MmsNotificationAnswer>("mms_notification")
            pair<MmsRetrievedRequest, MmsRetrievedAnswer>("mms_retrieved")
            pair<MmsSendReqRequest, MmsSendReqAnswer>("mms_send_req")
            pair<WaDigestRequest, WaDigestAnswer>("wa_digest")
            pair<WaReplyRequest, WaReplyAnswer>("wa_reply")
            pair<MsgRemindRequest, MsgRemindAnswer>("msg_remind")
            pair<TripFixRequest, TripFixAnswer>("trip_fix")
            pair<TripNoFixRequest, TripFixAnswer>("trip_no_fix")
            pair<TripAlarmRequest, TripAlarmAnswer>("trip_alarm")
            pair<TripEndRequest, TripEndAnswer>("trip_end")
        }

    private inline fun <reified Req, reified Ans> MutableMap<String, KSerializer<*>>.pair(kind: String) {
        put("headless.$kind.request", serializer<Req>())
        put("headless.$kind.answer", serializer<Ans>())
    }
}
