package com.relay.agents

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

data class GoogleUser(val name: String, val givenName: String, val email: String)

/**
 * تسجيل الدخول بحساب Google عبر Credential Manager (androidx.credentials) ومكتبة Google Identity Services الرسمية.
 * يعطي هوية المستخدم فقط (الاسم والبريد)؛ لا يمنح وصولاً إلى Drive أو Gmail، ولا يُحفظ رمز الهوية.
 * يتطلب Web client ID من Google Cloud Console، وعميل OAuth من نوع Android بنفس الحزمة وبصمة التوقيع.
 */
object GoogleAuth {
    /** يجب أن يكون context هو Activity ليظهر حوار اختيار الحساب. */
    suspend fun signIn(context: Context, webClientId: String): Result<GoogleUser> = runCatching {
        val option = GetSignInWithGoogleOption.Builder(webClientId).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val cred = CredentialManager.create(context).getCredential(context, request).credential
        if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            val g = GoogleIdTokenCredential.createFrom(cred.data)
            GoogleUser(g.displayName.orEmpty(), g.givenName.orEmpty(), g.id)
        } else {
            error("Unexpected credential type")
        }
    }

    suspend fun signOut(context: Context) {
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
    }
}
