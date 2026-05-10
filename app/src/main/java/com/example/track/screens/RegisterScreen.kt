package com.example.track.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.example.track.network.RegisterRequest
import com.example.track.network.RetrofitClient
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

@Composable
fun RegisterScreen(navController: NavHostController) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var selectedRole by remember { mutableStateOf<String?>(null) } // No role selected initially
    var isLoading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "Create Account", fontSize = 32.sp, style = MaterialTheme.typography.headlineLarge)
        Spacer(modifier = Modifier.height(24.dp))

        // Role Selection at the top
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { selectedRole = "student" },
                modifier = Modifier.weight(1f),
                colors = if (selectedRole == "student") ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors()
            ) {
                Text("Student")
            }
            Button(
                onClick = { selectedRole = "driver" },
                modifier = Modifier.weight(1f),
                colors = if (selectedRole == "driver") ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors()
            ) {
                Text("Driver")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Full Name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(24.dp))

        if (isLoading) {
            CircularProgressIndicator()
        } else {
            Button(
                onClick = {
                    val role = selectedRole ?: "student"
                    scope.launch {
                        isLoading = true
                        message = null
                        try {
                            val response = RetrofitClient.instance.register(
                                RegisterRequest(name, email, role, password)
                            )
                            if (response.isSuccessful) {
                                message = "Registration successful as $role! Please login."
                                isError = false
                                // Optional: auto navigate to login
                            } else {
                                val errorJson = response.errorBody()?.string()
                                val errorMsg = try {
                                    JSONObject(errorJson ?: "").getString("msg")
                                } catch (e: Exception) {
                                    "Registration failed. Try again."
                                }
                                message = errorMsg
                                isError = true
                            }
                        } catch (e: Exception) {
                            message = "Error: ${e.message ?: "Connection failed"}"
                            isError = true
                        } finally {
                            isLoading = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedRole != null && name.isNotBlank() && email.isNotBlank() && password.isNotBlank()
            ) {
                val roleText = selectedRole?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() } ?: "Role"
                Text("Register as $roleText")
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            TextButton(onClick = { navController.popBackStack() }) {
                Text("Already have an account? Login")
            }
        }

        message?.let {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = it,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}
