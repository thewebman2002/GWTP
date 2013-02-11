/**
 * Copyright 2013 ArcBees Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.gwtplatform.dispatch.rebind.velocity;

import com.google.common.eventbus.EventBus;
import com.google.gwt.core.ext.UnableToCompleteException;
import com.google.gwt.core.ext.typeinfo.*;
import com.google.inject.assistedinject.Assisted;
import com.gwtplatform.dispatch.rebind.RegisterSerializerEvent;
import com.gwtplatform.dispatch.shared.Action;
import com.gwtplatform.dispatch.shared.rest.HttpMethod;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.ws.rs.*;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.gwtplatform.dispatch.client.rest.SerializedType.BODY;
import static com.gwtplatform.dispatch.client.rest.SerializedType.RESPONSE;

public class RestActionGenerator extends AbstractVelocityGenerator {
    private static class AnnotatedMethodParameter {
        private JParameter parameter;
        private String fieldName;

        private AnnotatedMethodParameter(JParameter parameter, String fieldName) {
            this.parameter = parameter;
            this.fieldName = fieldName;
        }
    }

    private interface AnnotationValueResolver<T extends Annotation> {
        String resolve(T annotation);
    }

    @SuppressWarnings("unchecked")
    private static final List<Class<? extends Annotation>> PARAM_ANNOTATIONS =
            Arrays.asList(HeaderParam.class, QueryParam.class, PathParam.class, FormParam.class);

    private static final String PATH_PARAM = "{%s}";
    private static final String PATH_PARAM_MISSING = "@PathParam(\"%1$s\") declared, but '%1$s' not found in %2$s.";
    private static final String MANY_REST_ANNOTATIONS = "'%s' parameter's '%s' is annotated with more than one REST " +
            "annotations.";
    private static final String MANY_POTENTIAL_BODY = "%s has more than one potential body parameter.";
    private static final String FORM_AND_BODY_PARAM = "%s has both @FormParam and a body parameter. You must specify " +
            "one or the other.";

    private final EventBus eventBus;
    private GeneratorFactory generatorFactory;
    private final JMethod actionMethod;
    private final JType returnType;
    private final List<AnnotatedMethodParameter> pathParams = new ArrayList<AnnotatedMethodParameter>();
    private final List<AnnotatedMethodParameter> headerParams = new ArrayList<AnnotatedMethodParameter>();
    private final List<AnnotatedMethodParameter> queryParams = new ArrayList<AnnotatedMethodParameter>();
    private final List<AnnotatedMethodParameter> formParams = new ArrayList<AnnotatedMethodParameter>();
    private final List<JParameter> potentialBodyParams = new ArrayList<JParameter>();

    private HttpMethod httpMethod;
    private String path = "";
    private JParameter bodyParam;

    @Inject
    public RestActionGenerator(
            EventBus eventBus,
            TypeOracle typeOracle,
            Logger logger,
            Provider<VelocityContext> velocityContextProvider,
            VelocityEngine velocityEngine,
            GeneratorUtil generatorUtil,
            GeneratorFactory generatorFactory,
            @Assisted JMethod actionMethod) throws UnableToCompleteException {
        super(typeOracle, logger, velocityContextProvider, velocityEngine, generatorUtil);
        this.eventBus = eventBus;
        this.generatorFactory = generatorFactory;

        this.actionMethod = actionMethod;
        returnType = actionMethod.getReturnType();
    }

    public void generate() throws Exception {
        verifyIsAction();
        JClassType resultType = getResultType();

        retrieveConfigAnnonations();
        retrieveParameterConfig();
        retrieveBodyConfig();

        verifyPathParamsExist();

        generateSerializers(resultType);

//        writeClass(printWriter);
    }

    @Override
    protected void populateVelocityContext(VelocityContext velocityContext,
            JClassType descriptor) throws UnableToCompleteException {
    }

    private void generateSerializers(JClassType resultType) throws Exception {
        if (bodyParam != null) {
            String bodySerializer = generateSerializer(bodyParam.getType().isClassOrInterface());
            eventBus.post(new RegisterSerializerEvent(getQualifiedClassName(), BODY, bodySerializer));
        }

        String responseSerializer = generateSerializer(resultType);
        eventBus.post(new RegisterSerializerEvent(getQualifiedClassName(), RESPONSE, responseSerializer));
    }

    private String generateSerializer(JClassType type) throws Exception {
        SerializerGenerator generator = generatorFactory.create(type);
        return generator.generate();
    }

    private String getQualifiedClassName() {
        String className = getActionName() + returnType.isClassOrInterface().getName() + SUFFIX;
        return PACKAGE + "." + className;
    }

    private String getActionName() {
        StringBuilder nameBuilder = new StringBuilder(actionMethod.getName());
        Character firstChar = Character.toUpperCase(nameBuilder.charAt(0));
        nameBuilder.setCharAt(0, firstChar);

        StringBuilder classNameBuilder = new StringBuilder();
        classNameBuilder.append(actionMethod.getEnclosingType().getName())
                .append("_")
                .append(nameBuilder);

        for (JType type : actionMethod.getErasedParameterTypes()) {
            classNameBuilder.append("_").append(type.getSimpleSourceName());
        }

        return classNameBuilder.toString();
    }

    private void verifyIsAction() throws UnableToCompleteException {
        JClassType actionClass = null;

        try {
            actionClass = getTypeOracle().getType(Action.class.getName());
        } catch (NotFoundException e) {
            getLogger().die("Unable to find interface Action.");
        }

        JClassType returnClass = returnType.isClassOrInterface();
        if (!returnClass.isAssignableTo(actionClass)) {
            String typeName = returnClass.getQualifiedSourceName();
            getLogger().die(typeName + " must implement Action.");
        }
    }

    private void retrieveConfigAnnonations() throws UnableToCompleteException {
        retrieveHttpMethod();

        if (actionMethod.isAnnotationPresent(Path.class)) {
            path = concatenatePath(path, actionMethod.getAnnotation(Path.class).value());
        }
    }

    private void retrieveHttpMethod() throws UnableToCompleteException {
        Boolean moreThanOneAnnotation = false;

        if (actionMethod.isAnnotationPresent(GET.class)) {
            httpMethod = HttpMethod.GET;
        }

        if (actionMethod.isAnnotationPresent(POST.class)) {
            moreThanOneAnnotation = httpMethod != null;
            httpMethod = HttpMethod.POST;
        }

        if (actionMethod.isAnnotationPresent(PUT.class)) {
            moreThanOneAnnotation = moreThanOneAnnotation || httpMethod != null;
            httpMethod = HttpMethod.PUT;
        }

        if (actionMethod.isAnnotationPresent(DELETE.class)) {
            moreThanOneAnnotation = moreThanOneAnnotation || httpMethod != null;
            httpMethod = HttpMethod.DELETE;
        }

        if (actionMethod.isAnnotationPresent(HEAD.class)) {
            moreThanOneAnnotation = moreThanOneAnnotation || httpMethod != null;
            httpMethod = HttpMethod.HEAD;
        }

        if (httpMethod == null) {
            getLogger().die(actionMethod.getName() + " has no http method annotations.");
        } else if (moreThanOneAnnotation) {
            getLogger().warn(actionMethod.getName() + " has more than one http method annotation.");
        }
    }

    private void retrieveParameterConfig() throws UnableToCompleteException {
        JParameter[] parameters = actionMethod.getParameters();

        buildParamList(parameters, HeaderParam.class, new AnnotationValueResolver<HeaderParam>() {
            @Override
            public String resolve(HeaderParam annotation) {
                return annotation.value();
            }
        }, headerParams);

        buildParamList(parameters, PathParam.class, new AnnotationValueResolver<PathParam>() {
            @Override
            public String resolve(PathParam annotation) {
                return annotation.value();
            }
        }, pathParams);

        buildParamList(parameters, QueryParam.class, new AnnotationValueResolver<QueryParam>() {
            @Override
            public String resolve(QueryParam annotation) {
                return annotation.value();
            }
        }, queryParams);

        buildParamList(parameters, FormParam.class, new AnnotationValueResolver<FormParam>() {
            @Override
            public String resolve(FormParam annotation) {
                return annotation.value();
            }
        }, formParams);

        buildPotentialBodyParams();
    }

    private <T extends Annotation> void buildParamList(JParameter[] parameters, Class<T> annotationClass,
            AnnotationValueResolver<T> annotationValueResolver, List<AnnotatedMethodParameter> destination)
            throws UnableToCompleteException {
        List<Class<? extends Annotation>> restrictedAnnotations = getRestrictedAnnotations(annotationClass);

        for (JParameter parameter : parameters) {
            T parameterAnnotation = parameter.getAnnotation(annotationClass);

            if (parameterAnnotation != null) {
                if (hasAnnotationFrom(parameter, restrictedAnnotations)) {
                    getLogger().die(String.format(MANY_REST_ANNOTATIONS, actionMethod.getName(), parameter.getName()));
                    throw new UnableToCompleteException();
                }

                String value = annotationValueResolver.resolve(parameterAnnotation);
                destination.add(new AnnotatedMethodParameter(parameter, value));
            }
        }
    }

    private List<Class<? extends Annotation>> getRestrictedAnnotations(Class<? extends Annotation> allowedAnnotation) {
        List<Class<? extends Annotation>> restrictedAnnotations = new ArrayList<Class<? extends Annotation>>();
        restrictedAnnotations.addAll(PARAM_ANNOTATIONS);
        restrictedAnnotations.remove(allowedAnnotation);

        return restrictedAnnotations;
    }

    private Boolean hasAnnotationFrom(JParameter parameter, List<Class<? extends Annotation>> restrictedAnnotations) {
        for (Class<? extends Annotation> restrictedAnnotation : restrictedAnnotations) {
            if (parameter.isAnnotationPresent(restrictedAnnotation)) {
                return true;
            }
        }

        return false;
    }

    private void buildPotentialBodyParams() {
        Collections.addAll(potentialBodyParams, actionMethod.getParameters());

        List<AnnotatedMethodParameter> annotatedParameters = new ArrayList<AnnotatedMethodParameter>();
        annotatedParameters.addAll(headerParams);
        annotatedParameters.addAll(pathParams);
        annotatedParameters.addAll(queryParams);
        annotatedParameters.addAll(formParams);

        for (AnnotatedMethodParameter annotatedParameter : annotatedParameters) {
            potentialBodyParams.remove(annotatedParameter.parameter);
        }
    }

    private void verifyPathParamsExist() throws UnableToCompleteException {
        for (AnnotatedMethodParameter param : pathParams) {
            verifyPathParamExists(param.fieldName);
        }
    }

    private void verifyPathParamExists(String param) throws UnableToCompleteException {
        if (!path.contains(String.format(PATH_PARAM, param))) {
            String warning = String.format(PATH_PARAM_MISSING, param, path);
            getLogger().die(warning);
        }
    }

    private void retrieveBodyConfig() throws UnableToCompleteException {
        if (potentialBodyParams.isEmpty()) {
            return;
        }

        if (potentialBodyParams.size() > 1) {
            getLogger().die(String.format(MANY_POTENTIAL_BODY, actionMethod.getName()));
        }

        if (!formParams.isEmpty()) {
            getLogger().die(String.format(FORM_AND_BODY_PARAM, actionMethod.getName()));
        }

        bodyParam = potentialBodyParams.get(0);
    }

    private JClassType getResultType() throws UnableToCompleteException {
        JParameterizedType parameterized = returnType.isParameterized();
        if (parameterized == null || parameterized.getTypeArgs().length != 1) {
            getLogger().die("The action must specify a result type argument.");
        }
        return parameterized.getTypeArgs()[0];
    }
}