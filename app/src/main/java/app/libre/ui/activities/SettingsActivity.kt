package app.libre.ui.activities

import android.os.Bundle
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupWithNavController
import app.libre.R
import app.libre.databinding.ActivitySettingsBinding
import app.libre.ui.base.BaseActivity

class SettingsActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val binding = ActivitySettingsBinding.inflate(layoutInflater)
        val navController = binding.settings.getFragment<NavHostFragment>().navController
        setSupportActionBar(binding.toolbar)
        setContentView(binding.root)

        // ensure that the toolbar's back button is always visible
        val appBarConfiguration = AppBarConfiguration.Builder()
            .setFallbackOnNavigateUpListener {
                finish()
                true
            }
            .build()
        binding.toolbar.setupWithNavController(navController, appBarConfiguration)
    }
}
