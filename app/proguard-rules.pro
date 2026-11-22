# The default AndroidViewModel factory discovers this constructor through reflection.
-keepclassmembers class com.mobilechat.app.ChatViewModel {
    public <init>(android.app.Application, androidx.lifecycle.SavedStateHandle);
}
