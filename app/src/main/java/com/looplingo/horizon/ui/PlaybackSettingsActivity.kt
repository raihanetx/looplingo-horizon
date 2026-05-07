package com.looplingo.horizon.ui

import android.os.Bundle
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.looplingo.horizon.R
import com.looplingo.horizon.databinding.ActivityPlaybackSettingsBinding
import com.looplingo.horizon.playback.AudioPlaybackService
import com.looplingo.horizon.playback.LoopMode

class PlaybackSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPlaybackSettingsBinding
    private var currentLoopMode: LoopMode = LoopMode.LOOP_INFINITE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlaybackSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupLoopModeSpinner()
        setupStartActionRadio()
        setupApplyButton()
        updateLoopCountVisibility()
    }

    private fun setupLoopModeSpinner() {
        val modes = arrayOf("Play Once", "Loop X Times", "Loop Infinite", "Flow", "Auto-Loop", "A-B Pin")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerLoopMode.adapter = adapter

        binding.spinnerLoopMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                currentLoopMode = when (position) {
                    0 -> LoopMode.PLAY_ONCE
                    1 -> LoopMode.LOOP_X_TIMES
                    2 -> LoopMode.LOOP_INFINITE
                    3 -> LoopMode.FLOW
                    4 -> LoopMode.AUTO_LOOP
                    5 -> LoopMode.A_B_PIN
                    else -> LoopMode.LOOP_INFINITE
                }
                updateLoopCountVisibility()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupStartActionRadio() {
        binding.rbAutoplay.isChecked = true
    }

    private fun updateLoopCountVisibility() {
        binding.layoutLoopCount.visibility = if (currentLoopMode == LoopMode.LOOP_X_TIMES) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
    }

    private fun setupApplyButton() {
        binding.btnApply.setOnClickListener {
            val startAction = if (binding.rbAutoplay.isChecked) 0 else 1
            val loopCount = binding.etLoopCount.text.toString().toIntOrNull() ?: 10
            val rangeStart = binding.etRangeStart.text.toString().toLongOrNull() ?: 0
            val rangeEnd = binding.etRangeEnd.text.toString().toLongOrNull() ?: -1
            val autoAdvance = binding.cbAutoAdvance.isChecked

            // Pass settings to service or save to preferences
            // For now, just finish and return to main
            finish()
        }
    }
}
