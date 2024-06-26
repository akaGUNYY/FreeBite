package com.example.freebite2.ui.activity

import android.Manifest
import android.app.ProgressDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.scale
import com.bumptech.glide.Glide
import com.example.freebite2.R
import com.example.freebite2.databinding.ActivityAddOffreBinding
import com.example.freebite2.model.OffreModel
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.UUID

class AddOffreActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityAddOffreBinding
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var googleMap: GoogleMap
    private lateinit var dialogueProgress: ProgressDialog
    private lateinit var dialogue_Progress: ProgressDialog

    private var imageUri: Uri? = null
    private var imageUploadOffre: Uri? = null
    private lateinit var auth: FirebaseAuth

    private val requestCameraPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions[Manifest.permission.CAMERA] == true && permissions[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true) {
            pickImageCamera()
        } else {
            Toast.makeText(this, "Camera & Storage permissions are required", Toast.LENGTH_SHORT).show()
        }
    }

    private val requestStoragePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            pickImageGallery()

        } else {
            Toast.makeText(this, "Storage permission is required", Toast.LENGTH_SHORT).show()
        }
    }

    private val cameraActivityResultLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            binding.uploadImgLayout.visibility = android.view.View.GONE
            binding.offerPicLayout.visibility = android.view.View.VISIBLE
            imageUri?.let { uploadImageToFirebase(it) }
        } else {
            Toast.makeText(this, "Camera capture failed", Toast.LENGTH_SHORT).show()
        }
    }

    private val galleryActivityResultLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            imageUri = uri
            binding.uploadImgLayout.visibility = android.view.View.GONE
            binding.offerPicLayout.visibility = android.view.View.VISIBLE
            uploadImageToFirebase(uri)
        }
    }

    private val PERMISSIONS_REQUEST_CODE = 100
    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddOffreBinding.inflate(layoutInflater)
        setContentView(binding.root)

        dialogueProgress = ProgressDialog(this)
        dialogueProgress.setMessage("Uploading Image...")
        dialogueProgress.setCancelable(false)

        dialogue_Progress = ProgressDialog(this)
        dialogue_Progress.setMessage(" ")
        dialogue_Progress.setCancelable(false)

        auth = FirebaseAuth.getInstance()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        checkGPSStatus()

        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSIONS_REQUEST_CODE)
        }

        val mapFragment = supportFragmentManager.findFragmentById(R.id.offerPosition) as SupportMapFragment
        mapFragment.getMapAsync(this)

        binding.uploadImgBtn.setOnClickListener {
            imagePickDialog()
        }
        binding.offerPic.setOnClickListener {
            imagePickDialog()
        }
        binding.addOfferBtn.setOnClickListener {
            saveOfferToFirebase()
        }
    }

    override fun onResume() {
        super.onResume()
        checkGPSStatus()
        // Refresh map after checking GPS status
        val mapFragment = supportFragmentManager.findFragmentById(R.id.offerPosition) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    private fun hasPermissions(): Boolean {
        for (permission in REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return true
    }

    private fun imagePickDialog() {
        val options = arrayOf("Camera", "Gallerie")
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Choisir d'après la gallerie ou la caméra")
        builder.setItems(options) { _, which ->
            when (which) {
                0 -> {
                    Log.d(ContentValues.TAG, "imagePickDialog: Camera Clicked")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pickImageCamera()
                    } else {
                        requestCameraPermissions.launch(
                            arrayOf(
                                Manifest.permission.CAMERA,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                            )
                        )
                    }
                }
                1 -> {
                    Log.d(ContentValues.TAG, "imagePickDialog: Gallery Clicked")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pickImageGallery()
                    } else {
                        requestStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                }
            }
        }
        builder.setOnCancelListener {
            binding.uploadImgBtn.isEnabled = true
        }
        builder.show()
    }

    private fun checkGPSStatus() {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            showGPSDisabledDialog()
            refresh()
        }
    }

    private fun refresh() {
        val mapFragment = supportFragmentManager.findFragmentById(R.id.offerPosition) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    private fun showGPSDisabledDialog() {
        android.app.AlertDialog.Builder(this)
            .setMessage("Le GPS est désactivé. Voulez-vous l'activer ?")
            .setCancelable(false)
            .setPositiveButton("Oui") { _, _ ->
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .setNegativeButton("Non") { dialog, _ ->
                dialog.cancel()
            }
            .create()
            .show()
    }

    private fun pickImageCamera() {
        val values = ContentValues()
        values.put(MediaStore.Images.Media.TITLE, "New Picture")
        values.put(MediaStore.Images.Media.DESCRIPTION, "From your Camera")
        imageUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        imageUri?.let { cameraActivityResultLauncher.launch(it) }
    }

    private fun pickImageGallery() {
        galleryActivityResultLauncher.launch("image/*")
    }

    private fun uploadImageToFirebase(uri: Uri) {
        val userId = auth.currentUser?.uid ?: return
        val storageReference = FirebaseStorage.getInstance().getReference("offer_images/${UUID.randomUUID()}.jpg")

        dialogueProgress.show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                storageReference.putFile(uri).await()
                val downloadUri = storageReference.downloadUrl.await()
                imageUploadOffre = downloadUri
                withContext(Dispatchers.Main) {
                    Glide.with(this@AddOffreActivity).load(imageUri).into(binding.offerPic)
                    dialogueProgress.dismiss()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AddOffreActivity, "Failed to upload image: ${e.message}", Toast.LENGTH_SHORT).show()
                    dialogueProgress.dismiss()
                }
            }
        }
    }

    private fun saveOfferToFirebase() {
        val userId = auth.currentUser?.uid
        val userName = auth.currentUser?.displayName
        if (userId == null) {
            Toast.makeText(this, "Utilisateur non authentifié", Toast.LENGTH_SHORT).show()
            return
        }

        val title = binding.titreRepas.text.toString().trim()
        val description = binding.descritptionRepas.text.toString().trim()
        val duration = binding.horaireRepas.text.toString().trim()

        if (title.isEmpty() || description.isEmpty() || duration.isEmpty() || imageUploadOffre == null) {
            Toast.makeText(this, "Veuillez remplir tous les champs et sélectionner une image", Toast.LENGTH_SHORT).show()
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location: Location? ->
                    if (location != null) {
                        val offreModel = OffreModel(
                            providerID = userId,
                            nameoffre = title,
                            details = description,
                            duration = duration,
                            latitude = location.latitude,
                            longitude = location.longitude,
                            pictureUrl = imageUploadOffre.toString(),
                            offerID = null
                        )

                        val offreRef = FirebaseDatabase.getInstance().getReference("offres").push()
                        offreModel.offerID = offreRef.key
                        offreRef.setValue(offreModel)
                            .addOnSuccessListener {
                                Toast.makeText(this, "Offre ajoutée avec succès", Toast.LENGTH_SHORT).show()
                                finish()
                            }
                            .addOnFailureListener { e ->
                                Toast.makeText(this, "Échec de l'ajout de l'offre: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                    } else {
                        Toast.makeText(this, "Échec de la récupération de la localisation", Toast.LENGTH_SHORT).show()
                    }
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Échec de la récupération de la localisation: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), PERMISSIONS_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                getCurrentLocation()
            } else {
                Toast.makeText(this, "Permissions required", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        getCurrentLocation()
    }

    private fun getCurrentLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location: Location? ->
                    if (location != null) {
                        val latLng = LatLng(location.latitude, location.longitude)
                        val bitmap = BitmapFactory.decodeResource(resources, R.drawable.map_marker_user).scale(100, 100)
                        val markerOptions = MarkerOptions().position(latLng)
                            .title("Votre offre")
                            .icon(BitmapDescriptorFactory.fromBitmap(bitmap))
                        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
                        googleMap.addMarker(markerOptions)
                    }
                }
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), PERMISSIONS_REQUEST_CODE)
        }
    }
}
