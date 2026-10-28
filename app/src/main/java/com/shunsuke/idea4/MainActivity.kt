package com.shunsuke.idea4

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope

class MainActivity : ComponentActivity() {
    private val vm: AppVm by viewModels()
    private var afterMic: (() -> Unit)? = null
    private var importTrack = -1

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val i = importTrack
            importTrack = -1
            if (uri != null && i >= 0) vm.importFile(uri, i)
        }

    private val micLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            val action = afterMic
            afterMic = null
            if (ok) {
                if (action != null) action()
            } else {
                vm.say("マイクの許可がないと録音できません。端末の設定で許可してください。")
            }
        }

    private val authLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
            try {
                val r = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(res.data)
                val t = r.accessToken
                if (t != null) vm.sync(t) else vm.syncStatus = "ログインできませんでした。"
            } catch (e: Exception) {
                vm.syncStatus = "ログインできませんでした（" + (e.message ?: "原因不明") + "）。"
            }
        }

    private fun requestMic(action: () -> Unit) {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            action()
        } else {
            afterMic = action
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun driveSignIn() {
        vm.syncStatus = "ログイン中…"
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/drive.file")))
            .build()
        Identity.getAuthorizationClient(this).authorize(request)
            .addOnSuccessListener { r ->
                if (r.hasResolution()) {
                    val pi = r.pendingIntent
                    if (pi != null) {
                        authLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                    } else {
                        vm.syncStatus = "ログインできませんでした。"
                    }
                } else {
                    val t = r.accessToken
                    if (t != null) vm.sync(t) else vm.syncStatus = "ログインできませんでした。"
                }
            }
            .addOnFailureListener { e ->
                vm.syncStatus = "ログインできませんでした（" + (e.message ?: "原因不明") +
                    "）。Google Cloudの設定を確認してください。"
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            IdeaTheme {
                IdeaApp(
                    vm,
                    { action -> requestMic(action) },
                    { driveSignIn() },
                    { i ->
                        importTrack = i
                        filePicker.launch(arrayOf("audio/*", "application/ogg"))
                    },
                )
            }
        }
    }
}
