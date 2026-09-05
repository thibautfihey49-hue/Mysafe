package com.mysafe.mysafe

import android.content.Context
import android.location.Location
import android.telephony.SmsManager
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.ArrayList

// ==========================================
// 🎯 STRUCTURES DU SYSTÈME DE RÈGLES
// ==========================================

// Un DÉCLENCHEUR : ce qui démarre la macro
sealed class Trigger {
    data class SmsTrigger(val contains: String) : Trigger()
    data class TimeTrigger(val heure: String) : Trigger()
    object OnStartupTrigger : Trigger()
}

// Une CONDITION : ce qui doit être vrai
sealed class Condition {
    object AlwaysTrue : Condition()
    data class NumeroAutorise(val numero: String) : Condition()
    object GpsActif : Condition()
    object BatterieSuffisante : Condition()
}

// Une ACTION : ce que fait la macro
sealed class Action {
    object DemarrerGPS : Action()
    object ArreterGPS : Action()
    object DemarrerCamera : Action()
    data class EnvoyerPosition(val destinataire: String) : Action()
    data class EnvoyerSMS(val destinataire: String, val message: String) : Action()
    object SonnerieMax : Action()
    object VerrouillerEcran : Action()
}

// UNE MACRO COMPLÈTE
data class Macro(
    val nom: String,
    val declencheur: Trigger,
    val conditions: List<Condition> = emptyList(),
    val actions: List<Action>
)

// ==========================================
// 🧠 MOTEUR D'EXÉCUTION DES MACROS
// ==========================================
object MacroEngine {
    private const val TAG = "MySafe_Macro"
    private val macros = ArrayList<Macro>()
    private val numerosAutorises = HashSet<String>()
    
    var dernierPosition: Location? = null
    var estSuiviActif = false
    var contexte: Context? = null

    init {
        chargerMacrosParDefaut()
    }

    fun initialiser(context: Context) {
        this.contexte = context
        Log.d(TAG, "✅ Moteur de macros initialisé — ${macros.size} règles chargées")
    }

    private fun chargerMacrosParDefaut() {
        // 🎯 MACRO 1 : Demander position → Répondre immédiatement
        macros.add(Macro(
            nom = "Répondre position",
            declencheur = Trigger.SmsTrigger("MYSAFE_SEND_POS"),
            conditions = listOf(Condition.AlwaysTrue),
            actions = listOf(Action.EnvoyerPosition(""))
        ))

        // 🎯 MACRO 2 : Démarrer suivi GPS
        macros.add(Macro(
            nom = "Démarrer suivi GPS",
            declencheur = Trigger.SmsTrigger("MYSAFE_START_TRACK"),
            conditions = listOf(Condition.AlwaysTrue),
            actions = listOf(
                Action.DemarrerGPS,
                Action.EnvoyerSMS("", "MYSAFE_ACK:SUIVI_ACTIF")
            )
        ))

        // 🎯 MACRO 3 : Arrêter tout
        macros.add(Macro(
            nom = "Arrêter tout",
            declencheur = Trigger.SmsTrigger("MYSAFE_STOP"),
            conditions = listOf(Condition.AlwaysTrue),
            actions = listOf(
                Action.ArreterGPS,
                Action.EnvoyerSMS("", "MYSAFE_ACK:TOUT_ARRETE")
            )
        ))

        // 🎯 MACRO 4 : Allumer caméra
        macros.add(Macro(
            nom = "Allumer caméra",
            declencheur = Trigger.SmsTrigger("MYSAFE_CAMERA"),
            conditions = listOf(Condition.AlwaysTrue),
            actions = listOf(
                Action.DemarrerCamera,
                Action.EnvoyerSMS("", "MYSAFE_ACK:CAMERA_OK")
            )
        ))

        // 🎯 MACRO 5 : TOUT-EN-UN — Position + Caméra
        macros.add(Macro(
            nom = "Mode complet",
            declencheur = Trigger.SmsTrigger("MYSAFE_MACRO:TOUT"),
            conditions = listOf(Condition.AlwaysTrue),
            actions = listOf(
                Action.DemarrerGPS,
                Action.DemarrerCamera,
                Action.EnvoyerPosition(""),
                Action.EnvoyerSMS("", "MYSAFE_ACK:MACRO_TOUT_OK")
            )
        ))

        // 🎯 MACRO 6 : État du système
        macros.add(Macro(
            nom = "État système",
            declencheur = Trigger.SmsTrigger("MYSAFE_ETAT"),
            conditions = listOf(Condition.AlwaysTrue),
            actions = listOf(Action.EnvoyerSMS("", "ETAT:SUIVI=${if(estSuiviActif) "ACTIF" else "INACTIF"};GPS=${if(dernierPosition!=null) "OK" else "NON"}"))
        ))

        // 🎯 MACRO 7 : Ajouter numéro autorisé
        macros.add(Macro(
            nom = "Ajouter numéro autorisé",
            declencheur = Trigger.SmsTrigger("MYSAFE_AUTORISER:"),
            conditions = listOf(Condition.AlwaysTrue),
            actions = listOf(Action.EnvoyerSMS("", "NUMERO_AJOUTE"))
        ))

        Log.d(TAG, "✅ ${macros.size} macros par défaut chargées")
    }

    fun ajouterNumeroAutorise(numero: String) {
        val nettoye = numero.replace(Regex("[^0-9+]"), "")
        if (nettoye.length >= 6) {
            numerosAutorises.add(nettoye)
            Log.d(TAG, "✅ Numéro autorisé: $nettoye")
        }
    }

    fun estNumeroAutorise(numero: String): Boolean {
        val nettoye = numero.replace(Regex("[^0-9]"), "")
        return numerosAutorises.isEmpty() || numerosAutorises.any { nettoye.endsWith(it.replace(Regex("[^0-9]"), "")) }
    }

    // ==========================================
    // 📩 TRAITER UN SMS ENTRANT — C'EST ICI QUE TOUT SE DÉCIDE
    // ==========================================
    fun traiterSMS(contenu: String, numeroExpediteur: String): Boolean {
        Log.d(TAG, "📨 Analyse SMS de $numeroExpediteur : $contenu")

        // ✅ Vérifier autorisation
        if (!estNumeroAutorise(numeroExpediteur)) {
            Log.w(TAG, "❌ Numéro non autorisé : $numeroExpediteur")
            return false
        }

        // ⚙️ Commande spéciale : ajouter numéro autorisé
        if (contenu.startsWith("MYSAFE_AUTORISER:")) {
            val nouveauNum = contenu.removePrefix("MYSAFE_AUTORISER:").trim()
            ajouterNumeroAutorise(nouveauNum)
            envoyerSMS(numeroExpediteur, "MYSAFE_ACK:AUTORISE=$nouveauNum")
            return true
        }

        // 🔍 Chercher quelle macro correspond
        var macroTrouvee = false
        for (macro in macros) {
            if (verifierDeclencheur(macro.declencheur, contenu)) {
                Log.d(TAG, "🎯 MACRO DÉCLENCHÉE : ${macro.nom}")
                
                if (verifierConditions(macro.conditions, numeroExpediteur)) {
                    executerActions(macro.actions, numeroExpediteur)
                    macroTrouvee = true
                } else {
                    Log.w(TAG, "⚠️ Conditions non remplies pour : ${macro.nom}")
                }
            }
        }

        return macroTrouvee
    }

    private fun verifierDeclencheur(declencheur: Trigger, contenuSMS: String): Boolean {
        return when (declencheur) {
            is Trigger.SmsTrigger -> contenuSMS.contains(declencheur.contains, ignoreCase = true)
            Trigger.OnStartupTrigger -> true
            is Trigger.TimeTrigger -> false
        }
    }

    private fun verifierConditions(conditions: List<Condition>, numero: String): Boolean {
        for (condition in conditions) {
            when (condition) {
                Condition.AlwaysTrue -> {}
                Condition.BatterieSuffisante -> {}
                Condition.GpsActif -> if (dernierPosition == null) return false
                is Condition.NumeroAutorise -> if (!estNumeroAutorise(condition.numero)) return false
            }
        }
        return true
    }

    private fun executerActions(actions: List<Action>, numeroReponse: String) {
        val ctx = contexte ?: return
        
        for (action in actions) {
            when (action) {
                Action.DemarrerGPS -> {
                    estSuiviActif = true
                    Log.d(TAG, "📍 ACTION : Démarrer le GPS")
                    MySafeAgentService.demarrerGPS(ctx)
                }
                Action.ArreterGPS -> {
                    estSuiviActif = false
                    Log.d(TAG, "📍 ACTION : Arrêter le GPS")
                    MySafeAgentService.arreterGPS(ctx)
                }
                Action.DemarrerCamera -> {
                    Log.d(TAG, "📹 ACTION : Démarrer la caméra")
                    val intent = android.content.Intent(ctx, StreamingActivity::class.java)
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                }
                is Action.EnvoyerPosition -> {
                    Log.d(TAG, "📤 ACTION : Envoyer la position")
                    envoyerPosition(numeroReponse)
                }
                is Action.EnvoyerSMS -> {
                    Log.d(TAG, "📤 ACTION : Envoyer SMS → ${action.message}")
                    envoyerSMS(numeroReponse, action.message)
                }
                Action.SonnerieMax -> {}
                Action.VerrouillerEcran -> {}
            }
        }
    }

    private fun envoyerSMS(destinataire: String, message: String) {
        if (destinataire.isBlank()) return
        
        try {
            SmsManager.getDefault().sendTextMessage(destinataire, null, message, null, null)
            Log.d(TAG, "📤 SMS → $destinataire : $message")
        } catch (e: Exception) {
            try {
                SmsManager.getDefault().sendDataMessage(
                    destinataire, null, 50006.toShort(),
                    message.toByteArray(Charsets.UTF_8), null, null
                )
                Log.d(TAG, "📤 SMS données → $destinataire")
            } catch (e2: Exception) {
                Log.e(TAG, "❌ Échec envoi SMS", e2)
            }
        }
    }

    private fun envoyerPosition(destinataire: String) {
        val pos = dernierPosition ?: run {
            envoyerSMS(destinataire, "MYSAFE_ERREUR:PAS_DE_POSITION")
            return
        }
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val message = "MYSAFE_POS:${pos.latitude},${pos.longitude},$time"
        envoyerSMS(destinataire, message)
    }

    fun mettreAJourPosition(nouvellePosition: Location) {
        dernierPosition = nouvellePosition
        Log.d(TAG, "📍 Position mise à jour : ${nouvellePosition.latitude}, ${nouvellePosition.longitude}")
    }

    fun obtenirToutesMacros(): List<Macro> = macros
}
