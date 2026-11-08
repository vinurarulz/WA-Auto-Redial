package com.example.whatsappredial

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

data class WhatsAppContact(val dataId: Long, val name: String, val number: String)

class MainActivity : AppCompatActivity() {

    private var selectedContact: WhatsAppContact? = null
    private lateinit var btnStartStop: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        this.title = "WhatsApp Auto Redial"
        supportActionBar?.title = "WhatsApp Auto Redial"

        // 🔴 මේ කේතයෙන් තමයි ඇප් එක ඇතුළට ගියාම උඩින් පේන නම වෙනස් වෙන්නේ
                        val tvSelectedContact = findViewById<TextView>(R.id.tvSelectedContact)
        val btnPickContact = findViewById<Button>(R.id.btnPickContact)
        btnStartStop = findViewById(R.id.btnStartStop)
        val switchCallDuration = findViewById<Switch>(R.id.switchCallDuration)
        val tvNumCallsLabel = findViewById<TextView>(R.id.tvNumCallsLabel)
        val seekNumCalls = findViewById<SeekBar>(R.id.seekNumCalls)
        val tvIntervalLabel = findViewById<TextView>(R.id.tvIntervalLabel)
        val seekInterval = findViewById<SeekBar>(R.id.seekInterval)

        if (seekNumCalls.progress == 0) {
            seekNumCalls.progress = 10
            tvNumCallsLabel.text = "Number of calls: 10"
        }

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
            val perms = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, perms, 101)
            } else {
                showWhatsAppContactsDialog(tvSelectedContact)
            }
        }

        btnStartStop.setOnClickListener {
            if (WhatsAppRedialService.isRunning) {
                WhatsAppRedialService.stopRedial(this)
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

        WhatsAppRedialService.onStateChanged = { isRunning ->
            runOnUiThread {
                btnStartStop.text = if (isRunning) "STOP" else "START"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        btnStartStop.text = if (WhatsAppRedialService.isRunning) "STOP" else "START"
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

        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(40, 20, 40, 20)

        val searchBox = EditText(this)
        searchBox.hint = "Search contact name..."
        searchBox.setSingleLine()
        
        val listView = ListView(this)
        
        container.addView(searchBox)
        container.addView(listView)

        val labels = contacts.map { "${it.name} (${it.number})" }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        listView.adapter = adapter

        val dialog = AlertDialog.Builder(this)
            .setTitle("Select WhatsApp Contact")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .show()

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {}
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter.filter(s)
            }
        })

        listView.setOnItemClickListener { _, _, position, _ ->
            val selectedString = adapter.getItem(position)
            val selected = contacts.find { "${it.name} (${it.number})" == selectedString }
            if (selected != null) {
                selectedContact = selected
                tvSelectedContact.text = selectedString
            }
            dialog.dismiss()
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val prefString = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return prefString.contains("$packageName/${WhatsAppRedialService::class.java.name}")
    }
}
