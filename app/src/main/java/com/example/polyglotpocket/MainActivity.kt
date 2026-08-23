package com.example.polyglotpocket

import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import com.example.polyglotpocket.data.TokenStore
import com.example.polyglotpocket.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private lateinit var appBarConfiguration: AppBarConfiguration

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep one consistent light look, even when the system is in dark mode.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHost = supportFragmentManager.findFragmentById(R.id.main) as NavHostFragment
        navController = navHost.navController

        setSupportActionBar(binding.toolbar)
        // Home is top-level (shows the drawer icon); other screens show the up arrow.
        appBarConfiguration = AppBarConfiguration(setOf(R.id.homeFragment), binding.drawerLayout)
        setupActionBarWithNavController(navController, appBarConfiguration)

        binding.navView.setNavigationItemSelectedListener { onDrawerItem(it) }

        // No toolbar/drawer on the login screen.
        navController.addOnDestinationChangedListener { _, destination, _ ->
            val isLogin = destination.id == R.id.loginFragment
            binding.toolbar.isVisible = !isLogin
            binding.drawerLayout.setDrawerLockMode(
                if (isLogin) DrawerLayout.LOCK_MODE_LOCKED_CLOSED else DrawerLayout.LOCK_MODE_UNLOCKED
            )
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        // On a sub-screen, let a fragment's back callback (e.g. training's
        // "leave?" prompt) handle the Up arrow too. Home keeps the drawer toggle.
        if (navController.currentDestination?.id != R.id.homeFragment &&
            onBackPressedDispatcher.hasEnabledCallbacks()
        ) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }

    private fun onDrawerItem(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_statistics ->
                Toast.makeText(this, R.string.coming_soon, Toast.LENGTH_SHORT).show()
            R.id.menu_logout -> logout()
        }
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    private fun logout() {
        lifecycleScope.launch {
            TokenStore.clear(this@MainActivity)
            navController.navigate(
                R.id.loginFragment,
                null,
                navOptions { popUpTo(R.id.nav_graph) { inclusive = true } }
            )
        }
    }
}
