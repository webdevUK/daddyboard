package helium314.keyboard.latin.gif

import android.content.Context
import com.facebook.drawee.backends.pipeline.Fresco

/**
 * Initializes Fresco for GIF loading.
 */
object GifImageLoader {
    
    @Volatile
    private var initialized = false
    
    fun initialize(context: Context) {
        if (initialized) return
        
        synchronized(this) {
            if (initialized) return
            
            Fresco.initialize(context.applicationContext)
            initialized = true
        }
    }
}
