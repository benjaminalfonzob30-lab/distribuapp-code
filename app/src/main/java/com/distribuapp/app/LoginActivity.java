package com.distribuapp.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.SignInButton;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;

import java.util.HashMap;
import java.util.Map;

/**
 * LoginActivity — Pantalla de inicio de sesión.
 *
 * Permite autenticarse de dos maneras:
 *   1. Correo electrónico + contraseña (Firebase Authentication)
 *   2. Cuenta Google (Single Sign-On con Firebase)
 *
 * Al autenticarse exitosamente, captura la posición GPS del dispositivo
 * y la almacena en Firebase Realtime Database bajo /usuarios/{uid}/gps.
 * Luego redirige a MenuActivity.
 */
public class LoginActivity extends AppCompatActivity {

    // ── Constantes ────────────────────────────────────────────────────────────
    /** Código de solicitud para el flujo de Google Sign-In */
    private static final int RC_SIGN_IN = 9001;
    /** Código de solicitud para el permiso de ubicación en tiempo de ejecución */
    private static final int REQUEST_LOCATION = 100;

    // ── Firebase y Google ─────────────────────────────────────────────────────
    private FirebaseAuth mAuth;
    private GoogleSignInClient mGoogleSignInClient;
    private FusedLocationProviderClient fusedLocationClient;

    // ── Vistas ────────────────────────────────────────────────────────────────
    private EditText etEmail;
    private EditText etPassword;

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        // Inicializar Firebase Authentication
        mAuth = FirebaseAuth.getInstance();

        // Inicializar cliente de ubicación GPS
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        // Referenciar las vistas del layout
        etEmail    = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);
        Button    btnLogin  = findViewById(R.id.btnLogin);
        SignInButton btnGoogle = findViewById(R.id.btnGoogle);

        // ── Configurar Google Sign-In (SSO) ───────────────────────────────────
        // Se solicita el ID token del cliente web configurado en Firebase Console
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(
                GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(getString(R.string.default_web_client_id))
                .requestEmail()
                .build();
        mGoogleSignInClient = GoogleSignIn.getClient(this, gso);

        // ── Listeners de botones ─────────────────────────────────────────────
        btnLogin.setOnClickListener(v -> {
            String email = etEmail.getText().toString().trim();
            String pass  = etPassword.getText().toString().trim();

            // Validar que los campos no estén vacíos
            if (email.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this,
                        "Por favor completa todos los campos.",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            loginConEmail(email, pass);
        });

        btnGoogle.setOnClickListener(v -> {
            // Iniciar el flujo de selección de cuenta Google
            Intent signInIntent = mGoogleSignInClient.getSignInIntent();
            startActivityForResult(signInIntent, RC_SIGN_IN);
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AUTENTICACIÓN CON EMAIL Y CONTRASEÑA
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Intenta autenticar al usuario usando Firebase con email y contraseña.
     * Si tiene éxito, llama a guardarGPSyNavegar().
     */
    private void loginConEmail(String email, String password) {
        mAuth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        // Autenticación exitosa → capturar GPS y navegar
                        guardarGPSyNavegar();
                    } else {
                        // Credenciales incorrectas o usuario no registrado
                        Toast.makeText(this,
                                "Correo o contraseña incorrectos.",
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AUTENTICACIÓN CON GOOGLE (SSO)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Recibe el resultado del Intent de Google Sign-In.
     * Extrae el ID token y lo usa para autenticar en Firebase.
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == RC_SIGN_IN) {
            Task<GoogleSignInAccount> task =
                    GoogleSignIn.getSignedInAccountFromIntent(data);
            try {
                // Obtener cuenta Google seleccionada por el usuario
                GoogleSignInAccount account = task.getResult(ApiException.class);
                autenticarConFirebaseGoogle(account.getIdToken());
            } catch (ApiException e) {
                Toast.makeText(this,
                        "Error al iniciar sesión con Google.",
                        Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * Usa el ID token de Google para crear una credencial de Firebase
     * y autenticar al usuario en Firebase Authentication.
     */
    private void autenticarConFirebaseGoogle(String idToken) {
        AuthCredential credential = GoogleAuthProvider.getCredential(idToken, null);
        mAuth.signInWithCredential(credential)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        guardarGPSyNavegar();
                    } else {
                        Toast.makeText(this,
                                "Error al autenticar con Google.",
                                Toast.LENGTH_SHORT).show();
                    }
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CAPTURA DE GPS Y NAVEGACIÓN
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Verifica el permiso de ubicación.
     * Si está concedido, obtiene la última posición GPS y la almacena en
     * Firebase Realtime Database. Luego abre MenuActivity.
     * Si no está concedido, solicita el permiso al usuario.
     */
    private void guardarGPSyNavegar() {
        // Verificar si el permiso de ubicación precisa ya fue otorgado
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            // Pedir permiso en tiempo de ejecución (requerido desde Android 6.0)
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQUEST_LOCATION);
            return;
        }

        // Obtener la última ubicación conocida del dispositivo
        fusedLocationClient.getLastLocation()
                .addOnSuccessListener(this, location -> {
                    if (location != null) {
                        // Construir mapa de datos con latitud, longitud y timestamp
                        Map<String, Object> gpsData = new HashMap<>();
                        gpsData.put("latitud",   location.getLatitude());
                        gpsData.put("longitud",  location.getLongitude());
                        gpsData.put("timestamp", ServerValue.TIMESTAMP);

                        // Obtener UID del usuario autenticado
                        String uid = mAuth.getCurrentUser().getUid();

                        // Almacenar en: /usuarios/{uid}/gps
                        FirebaseDatabase.getInstance()
                                .getReference("usuarios")
                                .child(uid)
                                .child("gps")
                                .setValue(gpsData)
                                .addOnSuccessListener(aVoid -> {
                                    // GPS guardado exitosamente → abrir menú
                                    abrirMenuPrincipal();
                                })
                                .addOnFailureListener(e -> {
                                    // Si falla el guardado, navegamos igual
                                    abrirMenuPrincipal();
                                });
                    } else {
                        // No hay ubicación disponible (GPS apagado o sin señal)
                        abrirMenuPrincipal();
                    }
                });
    }

    /**
     * Navega a MenuActivity y cierra LoginActivity para que
     * el usuario no pueda volver atrás con el botón Back.
     */
    private void abrirMenuPrincipal() {
        Intent intent = new Intent(LoginActivity.this, MenuActivity.class);
        startActivity(intent);
        finish(); // Cerrar LoginActivity para que no quede en la pila
    }

    /**
     * Callback del resultado de la solicitud de permisos en tiempo de ejecución.
     * Si el usuario otorgó el permiso, reintenta guardar el GPS.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LOCATION) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permiso otorgado → reintentar
                guardarGPSyNavegar();
            } else {
                // Permiso denegado → navegar sin GPS
                abrirMenuPrincipal();
            }
        }
    }
}
