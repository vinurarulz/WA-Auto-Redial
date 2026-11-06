package com.example.whatsappredial

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

data class WhatsAppContact(val dataId: Long, val name: String, val number: String)

class MainActivity : AppCompatActivity() {

    private var selectedContact: WhatsAppContact? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val tvSelectedContact = findViewById<TextView>(R.id.tvSelectedContact)
        val btnPickContact = findViewById<Button>(R.id.btnPickContact)
        val btnStartStop = findViewById<Button>(R.id.btnStartStop)
        val switchCallDuration = findViewById<Switch>(R.id.switchCallDuration)
        val tvNumCallsLabel = findViewById<TextView>(R.id.tvNumCallsLabel)
        val seekNumCalls = findViewById<SeekBar>(R.id.seekNumCalls)
        val tvIntervalLabel = findViewById<TextView>(R.id.tvIntervalLabel)
        val seekInterval = findViewById<SeekBar>(R.id.seekInterval)

        seekNumCalls.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvNumCallsLabel.text = "Number of calls: ${progress.coerceAtLeast(1)}"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekInterval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvIntervalLabel.text = "Interval between calls: ${progress.coerceAtLeast(1)} sec(s)"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnPickContact.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CONTACTS), 101)
            } else {
                showWhatsAppContactsDialog(tvSelectedContact)
            }
        }

        btnStartStop.setOnClickListener {
            if (WhatsAppRedialService.isRunning) {
                WhatsAppRedialService.stopRedial()
                btnStartStop.text = "START"
            } else {
                val contact = selectedContact
                if (contact == null) {
                    Toast.makeText(this, "Select a WhatsApp contact first", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!isAccessibilityEnabled()) {
                    Toast.makeText(this, "Enable WA Auto Redial in Accessibility Settings", Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    return@setOnClickListener
                }
                btnStartStop.text = "STOP"
                WhatsAppRedialService.startRedial(
                    context = this,
                    dataId = contact.dataId,
                    totalCalls = seekNumCalls.progress.coerceAtLeast(1),
                    intervalSec = seekInterval.progress.coerceAtLeast(1),
                    durationLimit = switchCallDuration.isChecked
                )
            }
        }
    }

    private fun showWhatsAppContactsDialog(tvSelectedContact: TextView) {
        val contacts = mutableListOf<WhatsAppContact>()
        val projection = arrayOf(ContactsContract.Data._ID, ContactsContract.Data.DISPLAY_NAME, ContactsContract.Data.DATA3)
        val selection = "${ContactsContract.Data.MIMETYPE} = ?"
        val selectionArgs = arrayOf("vnd.android.cursor.item/vnd.com.whatsapp.voip.call")

        contentResolver.query(ContactsContract.Data.CONTENT_URI, projection, selection, selectionArgs, "${ContactsContract.Data.DISPLAY_NAME} ASC")?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(ContactsContract.Data._ID)
            val nameCol = cursor.getColumnIndexOrThrow(ContactsContract.Data.DISPLAY_NAME)
            val numCol = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA3)
            while (cursor.moveToNext()) {
                contacts.add(WhatsAppContact(cursor.getLong(idCol), cursor.getString(nameCol) ?: "Unknown", cursor.getString(numCol) ?: ""))
            }
        }

        if (contacts.isEmpty()) {
            Toast.makeText(this, "No WhatsApp contacts found", Toast.LENGTH_SHORT).show()
            return
        }

        val labels = contacts.map { "${it.name} (${it.number})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select WhatsApp Contact")
            .setItems(labels) { _, which ->
                selectedContact = contacts[which]
                tvSelectedContact.text = "${contacts[which].name} (${contacts[which].number})"
            }
            .show()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val prefString = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return prefString.contains("$packageName/${WhatsAppRedialService::class.java.name}")
    }
}
