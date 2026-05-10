package com.example.track

import android.content.Context
import android.content.SharedPreferences

class SessionManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("user_session", Context.MODE_PRIVATE)

    fun saveSession(token: String, role: String, userId: String) {
        prefs.edit().apply {
            putString("auth_token", token)
            putString("user_role", role)
            putString("user_id", userId)
            putBoolean("is_logged_in", true)
            apply()
        }
    }

    fun isLoggedIn(): Boolean = prefs.getBoolean("is_logged_in", false)
    fun getRole(): String? = prefs.getString("user_role", null)
    fun getDriverId(): String? = prefs.getString("user_id", null)
    fun getToken(): String? = prefs.getString("auth_token", null)

    fun logout() {
        prefs.edit().clear().apply()
    }
}
