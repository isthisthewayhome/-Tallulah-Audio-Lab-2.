package com.tallulah.photolab

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.graphics.*
import android.provider.MediaStore
import android.content.ContentValues
import android.widget.*
import android.view.View
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.Interpreter
import kotlin.math.*

class MainActivity : Activity() {
    private lateinit var image: ImageView
    private lateinit var message: TextView
    private var source: Bitmap? = null
    private var result: Bitmap? = null
    private var comparing = false
    private val sliders = linkedMapOf("Brightness" to 0, "Contrast" to 0, "Saturation" to 0, "Shadows" to 0, "Warmth" to 0, "Sharpen" to 0, "Denoise" to 0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation=1; setPadding(12,12,12,12); setBackgroundColor(Color.rgb(22,22,30)) }
        val title = TextView(this).apply { text="TALLULAH PHOTO LAB"; textSize=22f; setTextColor(Color.WHITE) }
        root.addView(title)
        val bar = LinearLayout(this)
        fun addButton(name:String, f:()->Unit) {
            bar.addView(Button(this).apply { text=name; setOnClickListener { f() } }, LinearLayout.LayoutParams(0,-2,1f))
        }
        addButton("Open") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="image/*"; addCategory(Intent.CATEGORY_OPENABLE) },1) }
        addButton("Compare") { comparing = !comparing; image.setImageBitmap(if(comparing) source else result); message.text = if(comparing) "Showing original" else "Showing edited" }
        addButton("Save") { saveImage() }
        addButton("AI 4×") { aiUpscale() }
        root.addView(bar)
        image = ImageView(this).apply { scaleType=ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.DKGRAY) }
        root.addView(image,LinearLayout.LayoutParams(-1,0,1f))
        message = TextView(this).apply { text="Open a photo. All edits stay on your phone."; setTextColor(Color.LTGRAY) }
        root.addView(message)
        val scroll=ScrollView(this)
        val controls=LinearLayout(this).apply { orientation=1 }
        for(name in sliders.keys) {
            val label=TextView(this).apply { text="$name: 0"; setTextColor(Color.WHITE) }
            controls.addView(label)
            val slider=SeekBar(this).apply { max=200; progress=100 }
            controls.addView(slider)
            slider.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s:SeekBar?, p:Int, fromUser:Boolean) {
                    if(fromUser) { sliders[name]=p-100; label.text="$name: ${p-100}" }
                }
                override fun onStartTrackingTouch(s:SeekBar?) {}
                override fun onStopTrackingTouch(s:SeekBar?) { render() }
            })
        }
        scroll.addView(controls)
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
    }
    @Deprecated("Legacy result callback")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==1 && resultCode==RESULT_OK) {
            val uri=data?.data ?: return
            try {
                val decoded=contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) } ?: return
                val ratio=min(1.0,3072.0/max(decoded.width,decoded.height))
                source=Bitmap.createScaledBitmap(decoded,max(1,(decoded.width*ratio).toInt()),max(1,(decoded.height*ratio).toInt()),true).copy(Bitmap.Config.ARGB_8888,false)
                comparing = false
                render()
            } catch(e:Exception) { message.text="Open failed: ${e.message}" }
        }
    }
    private fun render() {
        val original=source ?: return
        val w=original.width; val h=original.height
        val pixels=IntArray(w*h)
        original.getPixels(pixels,0,w,0,0,w,h)
        val b=sliders["Brightness"]!!*1.8
        val contrast=(1.0+sliders["Contrast"]!!/100.0).coerceAtLeast(0.0)
        val sat=(1.0+sliders["Saturation"]!!/100.0).coerceAtLeast(0.0)
        val shadows=sliders["Shadows"]!!/100.0
        val warmth=sliders["Warmth"]!!*0.6
        for(i in pixels.indices) {
            val p=pixels[i]
            var r=Color.red(p).toDouble(); var g=Color.green(p).toDouble(); var bl=Color.blue(p).toDouble()
            val lum=0.2126*r+0.7152*g+0.0722*bl
            val boost=shadows*90.0*(1-lum/255.0).coerceIn(0.0,1.0)
            r=(r-128)*contrast+128+b+boost+warmth
            g=(g-128)*contrast+128+b+boost
            bl=(bl-128)*contrast+128+b+boost-warmth
            val gray=0.2126*r+0.7152*g+0.0722*bl
            fun clamp(v:Double)=v.roundToInt().coerceIn(0,255)
            pixels[i]=Color.argb(Color.alpha(p),clamp(gray+(r-gray)*sat),clamp(gray+(g-gray)*sat),clamp(gray+(bl-gray)*sat))
        }
        val denoise = sliders["Denoise"]!!.coerceAtLeast(0) / 100.0
        if (denoise > 0 && w > 2 && h > 2) {
            val copy = pixels.clone()
            for (y in 1 until h-1) for (x in 1 until w-1) {
                val i = y*w+x
                val center = copy[i]
                val neighbors = intArrayOf(copy[i-w], copy[i+w], copy[i-1], copy[i+1])
                fun smooth(shift:Int):Int {
                    val v=(center shr shift) and 255
                    val avg=neighbors.sumOf { (it shr shift) and 255 } / 4.0
                    val diff=abs(v-avg)
                    val weight=denoise * (1.0-diff/100.0).coerceIn(0.0,1.0)
                    return (v*(1-weight)+avg*weight).roundToInt().coerceIn(0,255)
                }
                pixels[i]=Color.argb(Color.alpha(center),smooth(16),smooth(8),smooth(0))
            }
        }
        val sharp=sliders["Sharpen"]!!/100.0
        if(sharp>0 && w>2 && h>2) {
            val copy=pixels.clone()
            for(y in 1 until h-1) for(x in 1 until w-1) {
                val i=y*w+x
                val c=copy[i]; val a=copy[i-w]; val d=copy[i+w]; val l=copy[i-1]; val r=copy[i+1]
                fun ch(shift:Int):Int {
                    val v=(c shr shift) and 255
                    val neighbor=(((a shr shift) and 255)+((d shr shift) and 255)+((l shr shift) and 255)+((r shr shift) and 255))/4.0
                    return (v+sharp*(v-neighbor)).roundToInt().coerceIn(0,255)
                }
                pixels[i]=Color.argb(Color.alpha(c),ch(16),ch(8),ch(0))
            }
        }
        result=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888)
        comparing = false
        image.setImageBitmap(result)
        message.text="Edited: $w × $h. Maximum working dimension: 3072 px. Denoise is not AI restoration."
    }

    private fun aiUpscale() {
        val bmp = result ?: run { message.text = "Open a photo first"; return }
        val asset = try { assets.open("upscale_x4.tflite") } catch (_: Exception) {
            message.text = "AI model not bundled yet. No fake upscale applied."
            return
        }
        Thread {
            try {
                val bytes = asset.use { it.readBytes() }
                val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
                model.put(bytes)
                model.rewind()
                Interpreter(model).use { interpreter ->
                    val inputShape = interpreter.getInputTensor(0).shape()
                    val outputShape = interpreter.getOutputTensor(0).shape()
                    require(inputShape.size == 4 && outputShape.size == 4 &&
                        inputShape[0] == 1 && outputShape[0] == 1 &&
                        inputShape[3] == 3 && outputShape[3] == 3 &&
                        outputShape[1] == inputShape[1] * 4 &&
                        outputShape[2] == inputShape[2] * 4) { "Model must be NHWC RGB 4×" }
                    require(interpreter.getInputTensor(0).dataType().toString() == "FLOAT32" &&
                        interpreter.getOutputTensor(0).dataType().toString() == "FLOAT32") {
                        "Model must use float32 tensors"
                    }
                    val h = inputShape[1]; val w = inputShape[2]
                    require(h > 0 && w > 0 && h * w <= 512 * 512) { "Unsupported model input dimensions" }
                    val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
                    val px = IntArray(w*h)
                    scaled.getPixels(px, 0, w, 0, 0, w, h)
                    val input = ByteBuffer.allocateDirect(w*h*3*4).order(ByteOrder.nativeOrder())
                    for (p in px) {
                        input.putFloat(Color.red(p).toFloat())
                        input.putFloat(Color.green(p).toFloat())
                        input.putFloat(Color.blue(p).toFloat())
                    }
                    input.rewind()
                    val oh = outputShape[1]; val ow = outputShape[2]
                    val output = ByteBuffer.allocateDirect(ow*oh*3*4).order(ByteOrder.nativeOrder())
                    interpreter.run(input, output)
                    output.rewind()
                    val out = IntArray(ow*oh)
                    for (i in out.indices) {
                        val r = output.float.coerceIn(0f,255f).roundToInt()
                        val g = (output.float.coerceIn(0f,1f)*255).roundToInt()
                        val b = (output.float.coerceIn(0f,1f)*255).roundToInt()
                        out[i] = Color.rgb(r,g,b)
                    }
                    val enhanced = Bitmap.createBitmap(out, ow, oh, Bitmap.Config.ARGB_8888)
                    runOnUiThread {
                        result = enhanced
                        comparing = false
                        image.setImageBitmap(enhanced)
                        message.text = "AI 4× output: ${ow} × ${oh}"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { message.text = "AI processing unavailable: ${e.message}" }
            }
        }.start()
        message.text = "Running offline AI model..."
    }
    private fun saveImage() {
        val bmp=result ?: run { message.text="Open a photo first"; return }
        try {
            val values=ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME,"Tallulah_${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE,"image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/TallulahPhotoLab")
            }
            val uri=contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values) ?: error("Storage unavailable")
            contentResolver.openOutputStream(uri).use { out -> requireNotNull(out); bmp.compress(Bitmap.CompressFormat.PNG,100,out) }
            message.text="Saved to Pictures/TallulahPhotoLab"
        } catch(e:Exception) { message.text="Save failed: ${e.message}" }
    }
}
