package ru.oopssre.gremlinamp;
import android.app.*;import android.content.Context;import android.graphics.Color;import android.graphics.Typeface;import android.graphics.drawable.GradientDrawable;import android.view.*;import android.widget.*;
/** Consistent application-owned dialogs, respecting the selected player theme. */
final class AppDialogBuilder extends AlertDialog.Builder {
 final int background,foreground,accent,line;
 AppDialogBuilder(Context c,int bg,int fg,int a,int edge){super(c);background=bg;foreground=fg;accent=a;line=edge;}
 void paint(View v){if(v instanceof TextView){TextView t=(TextView)v;t.setTextColor(v instanceof Button?accent:foreground);t.setTypeface(Typeface.DEFAULT);}if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)paint(g.getChildAt(i));}}
 @Override public AlertDialog create(){AlertDialog d=super.create();d.setOnShowListener(x->{GradientDrawable bg=new GradientDrawable();bg.setColor(background);bg.setCornerRadius(16*getContext().getResources().getDisplayMetrics().density);bg.setStroke(1,line);d.getWindow().setBackgroundDrawable(bg);paint(d.getWindow().getDecorView());});return d;}
 @Override public AlertDialog show(){AlertDialog d=create();d.show();return d;}
}
