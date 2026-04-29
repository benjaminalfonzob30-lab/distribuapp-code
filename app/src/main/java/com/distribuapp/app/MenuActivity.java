package com.distribuapp.app;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

/**
 * MenuActivity — Pantalla principal de la aplicación.
 *
 * Funcionalidades:
 *   1. Muestra el nombre/email del usuario autenticado.
 *   2. Calcula el costo de despacho según las reglas de negocio:
 *        - Compra >= $50.000 y distancia <= 20 km  → Gratis
 *        - Compra entre $25.000 y $49.999          → $150 × km
 *        - Compra < $25.000                         → $300 × km
 *   3. Muestra la temperatura del congelador del camión (desde Firebase)
 *      y emite una alerta si supera el límite permitido.
 *   4. Permite cerrar sesión y volver al Login.
 */
public class MenuActivity extends AppCompatActivity {

    // ── Constantes de temperatura ─────────────────────────────────────────────
    /** Temperatura máxima permitida para la cadena de frío (en °C) */
    private static final double TEMP_LIMITE = -10.0;

    // ── Firebase ──────────────────────────────────────────────────────────────
    private FirebaseAuth mAuth;

    // ── Vistas ────────────────────────────────────────────────────────────────
    private EditText etMonto;
    private EditText etKilometros;
    private TextView tvResultadoDespacho;
    private TextView tvBienvenida;
    private TextView tvTemperatura;

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_menu);

        mAuth = FirebaseAuth.getInstance();

        // Referenciar las vistas del layout
        tvBienvenida         = findViewById(R.id.tvBienvenida);
        etMonto              = findViewById(R.id.etMonto);
        etKilometros         = findViewById(R.id.etKilometros);
        tvResultadoDespacho  = findViewById(R.id.tvResultadoDespacho);
        tvTemperatura        = findViewById(R.id.tvTemperatura);
        Button btnCalcular   = findViewById(R.id.btnCalcular);
        Button btnCerrarSesion = findViewById(R.id.btnCerrarSesion);

        // Mostrar saludo con email del usuario autenticado
        mostrarBienvenida();

        // Iniciar monitoreo de temperatura del congelador
        monitorearTemperatura();

        // ── Listeners ─────────────────────────────────────────────────────────
        btnCalcular.setOnClickListener(v -> calcularDespacho());

        btnCerrarSesion.setOnClickListener(v -> cerrarSesion());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BIENVENIDA
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Muestra en pantalla el email del usuario actualmente autenticado.
     */
    private void mostrarBienvenida() {
        FirebaseUser usuario = mAuth.getCurrentUser();
        if (usuario != null) {
            tvBienvenida.setText("Bienvenido/a, " + usuario.getEmail());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CÁLCULO DE DESPACHO
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Lee el monto de compra y la distancia ingresados por el usuario,
     * aplica las reglas de negocio y muestra el costo de despacho calculado.
     *
     * Reglas:
     *   - Monto >= 50.000 y km <= 20 → Despacho GRATIS
     *   - Monto entre 25.000-49.999  → $150 por km
     *   - Monto < 25.000             → $300 por km
     */
    private void calcularDespacho() {
        String montoStr = etMonto.getText().toString().trim();
        String kmStr    = etKilometros.getText().toString().trim();

        // Validar que ambos campos tengan valor
        if (montoStr.isEmpty() || kmStr.isEmpty()) {
            Toast.makeText(this,
                    "Ingresa el monto y la distancia.",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        double monto = Double.parseDouble(montoStr);
        double km    = Double.parseDouble(kmStr);
        double costoDespacho;
        String mensaje;

        // ── Aplicar reglas de negocio ──────────────────────────────────────────
        if (monto >= 50000 && km <= 20) {
            // Compra mayor o igual a $50.000 dentro de 20 km → GRATIS
            costoDespacho = 0;
            mensaje = "¡Despacho GRATIS!\n(Compra >= $50.000 dentro de 20 km)";

        } else if (monto >= 25000) {
            // Compra entre $25.000 y $49.999 → $150 por km
            costoDespacho = km * 150;
            mensaje = String.format(
                    "Costo de despacho: $%,.0f\n($150 × %.1f km)",
                    costoDespacho, km);

        } else {
            // Compra menor a $25.000 → $300 por km
            costoDespacho = km * 300;
            mensaje = String.format(
                    "Costo de despacho: $%,.0f\n($300 × %.1f km)",
                    costoDespacho, km);
        }

        // Mostrar resultado en pantalla
        tvResultadoDespacho.setText(mensaje);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MONITOREO DE TEMPERATURA (CADENA DE FRÍO)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Escucha en tiempo real el nodo /camion/temperatura en Firebase.
     * Si el valor supera TEMP_LIMITE, muestra una alerta en pantalla.
     * Esto permite al administrador conocer el estado del congelador del camión.
     */
    private void monitorearTemperatura() {
        FirebaseDatabase.getInstance()
                .getReference("camion")
                .child("temperatura")
                .addValueEventListener(new ValueEventListener() {

                    @Override
                    public void onDataChange(DataSnapshot snapshot) {
                        if (snapshot.exists()) {
                            // Obtener temperatura actual desde Firebase
                            Double temp = snapshot.getValue(Double.class);
                            if (temp != null) {
                                String textoTemp = String.format("🌡️ Temperatura congelador: %.1f°C", temp);

                                if (temp > TEMP_LIMITE) {
                                    // ⚠️ Temperatura fuera de rango → alerta
                                    tvTemperatura.setText(textoTemp + "\n⚠️ ¡ALERTA! Temperatura fuera de rango.");
                                    tvTemperatura.setTextColor(
                                            getResources().getColor(android.R.color.holo_red_dark));
                                } else {
                                    // ✅ Temperatura dentro del rango permitido
                                    tvTemperatura.setText(textoTemp + "\n✅ Temperatura OK.");
                                    tvTemperatura.setTextColor(
                                            getResources().getColor(android.R.color.holo_green_dark));
                                }
                            }
                        } else {
                            tvTemperatura.setText("🌡️ Sin datos de temperatura disponibles.");
                        }
                    }

                    @Override
                    public void onCancelled(DatabaseError error) {
                        tvTemperatura.setText("Error al obtener temperatura.");
                    }
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CERRAR SESIÓN
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Cierra la sesión del usuario en Firebase Authentication
     * y redirige a LoginActivity.
     */
    private void cerrarSesion() {
        mAuth.signOut();
        Intent intent = new Intent(MenuActivity.this, LoginActivity.class);
        // Limpiar el back stack para que no pueda volver al menú sin autenticarse
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}
