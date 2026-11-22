# The default AndroidViewModel factory discovers this constructor through reflection.
-keepclassmembers class com.mobilechat.app.ChatViewModel {
    public <init>(android.app.Application, androidx.lifecycle.SavedStateHandle);
}

# Built-in LaTeX aliases (dfrac, tfrac, environments) instantiate this macro by reflection.
-keep class org.scilab.forge.jlatexmath.NewCommandMacro {
    public <init>();
    public java.lang.String executeMacro(org.scilab.forge.jlatexmath.TeXParser, java.lang.String[]);
}
