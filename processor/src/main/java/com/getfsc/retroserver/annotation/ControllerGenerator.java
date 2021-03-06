package com.getfsc.retroserver.annotation;

import com.getfsc.retroserver.BodyType;
import com.getfsc.retroserver.Route;
import com.getfsc.retroserver.http.RequestCaller;
import com.getfsc.retroserver.http.ServerRequest;
import com.getfsc.retroserver.util.StringUtil;
import com.squareup.javapoet.*;
import dagger.Module;
import dagger.Provides;
import okhttp3.RequestBody;
import retrofit2.http.*;

import javax.annotation.processing.ProcessingEnvironment;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Created by IntelliJ IDEA.
 * User: draco
 * Date: 16/4/11
 * Time: 下午5:18
 */
public class ControllerGenerator {

    private String pkg;
    private ProcessingEnvironment processingEnv;

    private List<String> controllerModules = new ArrayList<>();

    public ControllerGenerator(String pkg, ProcessingEnvironment processingEnv) {
        this.pkg = pkg;
        this.processingEnv = processingEnv;

    }



    public String build(TypeSpec clazz, String pkg, ProcessingEnvironment processingEnv) throws IOException {

        String fullName = pkg + "." + clazz.name;
        controllerModules.add(fullName);
        if (processingEnv.getElementUtils().getTypeElement(fullName) != null) {
            return fullName;
        }
        JavaFile javaFile = JavaFile.builder(pkg, clazz)
                .build();
        JavaFileObject jfo = processingEnv.getFiler().createSourceFile(fullName);
        try (Writer writer = jfo.openWriter()) {
            javaFile.writeTo(writer);
        }
        return fullName;
    }

    public TypeSpec addControllerElem(TypeElement controllerElem) {
        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(controllerElem.getSimpleName().toString() + "Module")
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Module.class);


        Controller anno = controllerElem.getAnnotation(Controller.class);
        String baseUrl = anno.value();
        boolean hasInject = ElementFilter.constructorsIn(controllerElem.getEnclosedElements())
                .stream().anyMatch(
                        element -> element.getAnnotation(Inject.class) != null);


        if (!hasInject) {
            TypeName type = TypeName.get(controllerElem.asType());

            MethodSpec methodSpec = MethodSpec.methodBuilder(StringUtil.decapitalize(controllerElem.getSimpleName().toString()))
                    .returns(type)
                    .addAnnotation(Singleton.class)
                    .addAnnotation(Provides.class)
                    .addStatement("return new $L()", type)
                    .build();
            classBuilder.addMethod(methodSpec);
        }

        Stream<ExecutableElement> m = controllerElem.getInterfaces().stream().flatMap(e -> {
            Element elem = ((DeclaredType) e).asElement();
            return ElementFilter.methodsIn(elem.getEnclosedElements()).stream();
        });

        List<Endpoint> endpointInterfaces = m.map(this::getVerbAndUrl).filter(e -> e != null).collect(Collectors.toList());

        TypeMirror current = controllerElem.asType();
        while (current.getKind() != TypeKind.NONE) {
            TypeElement e = (TypeElement) ((DeclaredType) current).asElement();
            ElementFilter.methodsIn(e.getEnclosedElements())
                    .stream()
                    .filter(method -> method.getModifiers().contains(Modifier.PUBLIC))
                    .forEach(method ->
                            endpointInterfaces.forEach(endPointInterface -> {
                                if (processingEnv.getElementUtils().overrides(method, endPointInterface.element, e)) {


                                    MethodSpec.Builder beforeRoute = MethodSpec.methodBuilder(method.getSimpleName().toString() + "Route")
                                            .addParameter(TypeName.get(e.asType()), "controller")
                                            .returns(Route.class)
                                            .addAnnotation(AnnotationSpec.builder(Provides.class)
                                                    .addMember("type", "Provides.Type.SET")
                                                    .build())
                                            .addStatement("$T route = new $T()", Route.class, Route.class);

                                    MethodSpec.Builder call = MethodSpec.methodBuilder("call")
                                            .addAnnotation(Override.class)
                                            .addModifiers(Modifier.PUBLIC)
                                            .addParameter(ServerRequest.class, "request")
                                            .returns(TypeName.get(method.getReturnType()));
                                    handleServerAnnotation(method, beforeRoute, call);


                                    String vars = endPointInterface.element.getParameters().stream().map(ve -> addParam(call, ve))
                                            .collect(Collectors.joining(", "));

                                    call.addStatement("return controller.$L($L)", method.getSimpleName().toString(), vars);


                                    TypeSpec.Builder caller = TypeSpec.anonymousClassBuilder("")
                                            .addSuperinterface(RequestCaller.class)
                                            .addMethod(call.build());

                                    MethodSpec methodSpec = beforeRoute
                                            .addStatement("route.setVerb($S)", endPointInterface.verb)
                                            .addStatement("route.setBaseUrl($S)", baseUrl)
                                            .addStatement("route.setUrl($S)", endPointInterface.url)
                                            .addStatement("route.setBodyType($T.$L)", BodyType.class, endPointInterface.bodyType)
                                            .addStatement("route.setCaller($L)", caller.build())
                                            .addStatement("return route")
                                            .build();
                                    classBuilder.addMethod(methodSpec);
                                }
                            }));
            current = e.getSuperclass();
        }

        return classBuilder.build();
    }

    private void handleServerAnnotation(ExecutableElement method, MethodSpec.Builder route, MethodSpec.Builder call) {

        ContentType contentType = method.getAnnotation(ContentType.class);
        if (contentType != null) {
            route.addStatement("route.addHeader($S)", "content-type: " + contentType.value());
        }
        ServerHeaders headers = method.getAnnotation(ServerHeaders.class);
        if (headers != null) {
            for (String header : headers.value()) {
                route.addStatement("route.addHeader($S)", header);
            }
        }

        for (AnnotationMirror mirror : method.getAnnotationMirrors()) {
            for (AnnotationMirror am : mirror.getAnnotationType().asElement().getAnnotationMirrors()) {
                if (am.getAnnotationType().toString().equals(AnnoProcess.class.getName())) {
                    for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : am.getElementValues().entrySet()) {
                        if ("value".equals(entry.getKey().getSimpleName().toString())) {
                            AnnotationValue obj = entry.getValue();

                            try {
                                Class processorClass = Class.forName(obj.getValue().toString());
                                AnnoProcessor processor = (AnnoProcessor) processorClass.newInstance();
                                Class annotationClass = Class.forName(mirror.getAnnotationType().toString());
                                processor.process(processingEnv, method.getAnnotation(annotationClass), route, call);
                            } catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
                                e.printStackTrace();
                            }
                            break;
                        }
                    }

                }
            }

        }
    }

    private String addParam(MethodSpec.Builder call, VariableElement ve) {
        TypeName varType = TypeName.get(ve.asType());
        Path path = ve.getAnnotation(Path.class);
        TypeName rawType;
        if (varType instanceof ParameterizedTypeName) {
            rawType = ((ParameterizedTypeName) varType).rawType;
        }else {
            rawType = varType;
        }
        String varname = ve.getSimpleName().toString();
        if (path != null) {
            call.addStatement("$T $L = request.path($S).get($T.class)",
                    varType, varname, path.value(), rawType);
            return varname;
        }
        Query query = ve.getAnnotation(Query.class);
        if (query != null) {
            call.addStatement("$T $L = request.query($S).get($T.class)",
                    varType, varname, query.value(), rawType);
            return varname;
        }
        Field field = ve.getAnnotation(Field.class);
        if (field != null) {
            call.addStatement("$T $L = request.field($S).get($T.class)",
                    varType, varname, field.value(), rawType);
            return varname;
        }

        Header header = ve.getAnnotation(Header.class);
        if (header != null) {
            call.addStatement("$T $L = request.header($S).get($T.class)",
                    varType, varname, header.value(), rawType);
            return varname;
        }

        QueryMap queryMap = ve.getAnnotation(QueryMap.class);
        if (queryMap != null) {
            call.addStatement("$T $L = request.queryMap()",
                    varType, varname);
            return varname;
        }

        Body body = ve.getAnnotation(Body.class);
        if (body != null) {

            call.addStatement("$T $L= request.body($T.class)", varType, varname, rawType);
            return varname;
        }

        Part part = ve.getAnnotation(Part.class);
        if (part != null) {
            call.addStatement("$T $L = request.part($S)",
                    RequestBody.class, varname, part.value());
            return varname;
        }

        //don't know how to set this value, may be it's body
        call.addStatement("$T $L= request.body($T.class)", varType, varname, rawType);

        return varname;
    }



    private static class Endpoint {
        ExecutableElement element;
        String verb;
        String url;
        BodyType bodyType = BodyType.DEFAULT;

        public Endpoint(ExecutableElement element, String verb, String url) {
            this.element = element;
            this.verb = verb;
            this.url = url;
        }
    }

    private Endpoint getVerbAndUrl(ExecutableElement element) {

        Endpoint endpoint = null;

        GET get = element.getAnnotation(GET.class);
        if (get != null) {
            endpoint = new Endpoint(element, "GET", get.value());
        }

        HEAD head = element.getAnnotation(HEAD.class);
        if (head != null) {
            endpoint = new Endpoint(element, "HEAD", head.value());
        }

        POST post = element.getAnnotation(POST.class);
        if (post != null) {
            endpoint = new Endpoint(element, "POST", post.value());
        }

        PUT put = element.getAnnotation(PUT.class);
        if (put != null) {
            endpoint = new Endpoint(element, "PUT", put.value());
        }

        DELETE delete = element.getAnnotation(DELETE.class);
        if (delete != null) {
            endpoint = new Endpoint(element, "DELETE", delete.value());
        }

        PATCH patch = element.getAnnotation(PATCH.class);
        if (patch != null) {
            endpoint = new Endpoint(element, "PATCH", patch.value());
        }
        if (endpoint != null) {
            FormUrlEncoded form = element.getAnnotation(FormUrlEncoded.class);
            if (form != null) {
                endpoint.bodyType = BodyType.FORM_URL_ENCODED;
            } else {
                Multipart part = element.getAnnotation(Multipart.class);
                if (part != null)
                    endpoint.bodyType = BodyType.MULTIPART;
            }
        }


        return endpoint;
    }
}
