package sample.processor;

import java.io.IOException;
import java.io.Writer;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;

/** Generates sample.app.Info with a constant this source decides. */
@SupportedAnnotationTypes("*")
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public class InfoProcessor extends AbstractProcessor {
    private boolean done;

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        if (done) {
            return false;
        }
        done = true;
        try (Writer w = processingEnv.getFiler().createSourceFile("sample.app.Info").openWriter()) {
            w.write("package sample.app;\npublic class Info { public static final String GENERATED_BY = \"processor v1\"; }\n");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return false;
    }
}
