/*
 * Copyright 2019 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.smallrye.graphql.execution.datafetchers;

import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.Collections;
import java.util.Optional;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.Type;
import org.jboss.logging.Logger;

import graphql.schema.DataFetchingEnvironment;
import graphql.schema.PropertyDataFetcher;
import io.smallrye.graphql.schema.Annotations;
import io.smallrye.graphql.schema.helper.FormatHelper;

/**
 * Extending the default property data fetcher and take the annotations into account
 * TODO: Make a general annotation for marshaling/unmarshaling
 * 
 * @author Phillip Kruger (phillip.kruger@redhat.com)
 */
public class AnnotatedPropertyDataFetcher extends PropertyDataFetcher {
    private static final Logger LOG = Logger.getLogger(AnnotatedPropertyDataFetcher.class.getName());
    private final FormatHelper formatHelper = new FormatHelper();

    private DateTimeFormatter dateTimeFormatter = null;
    private NumberFormat numberFormat = null;
    private final Type type;

    public AnnotatedPropertyDataFetcher(String propertyName, Type type, Annotations annotations) {
        super(propertyName);
        this.type = type;
        if (formatHelper.isDateLikeTypeOrCollectionThereOf(type)) {
            if (annotations.containsOnOfTheseKeys(Annotations.JSONB_DATE_FORMAT)) {
                AnnotationInstance jsonbDateFormatAnnotation = annotations.getAnnotation(Annotations.JSONB_DATE_FORMAT);
                this.dateTimeFormatter = formatHelper.getDateFormat(type, jsonbDateFormatAnnotation);
            }
        }

        if (formatHelper.isNumberLikeTypeOrCollectionThereOf(type)) {
            if (annotations.containsOnOfTheseKeys(Annotations.JSONB_NUMBER_FORMAT)) {
                AnnotationInstance jsonbNumberFormatAnnotation = annotations.getAnnotation(Annotations.JSONB_NUMBER_FORMAT);
                this.numberFormat = formatHelper.getNumberFormat(jsonbNumberFormatAnnotation);
            }
        }
    }

    @Override
    public Object get(DataFetchingEnvironment environment) {
        Object o = super.get(environment);

        if (Optional.class.isInstance(o)) {
            LOG.error("type = " + type.kind());

            Optional optional = Optional.class.cast(o);
            if (optional.isPresent()) {
                Object value = optional.get();
                return Collections.singletonList(handleFormatting(value));
            } else {
                return Collections.emptyList();
            }
        } else {
            return handleFormatting(o);
        }
    }

    private Object handleFormatting(Object o) {
        if (dateTimeFormatter != null) {
            return handleDateFormatting(o);
        } else if (numberFormat != null) {
            return handleNumberFormatting(o);
        } else {
            return o;
        }
    }

    private Object handleDateFormatting(Object o) {
        if (TemporalAccessor.class.isInstance(o)) {
            TemporalAccessor temporalAccessor = (TemporalAccessor) o;
            return dateTimeFormatter.format(temporalAccessor);
        } else {
            // TODO: Either split input and output fetchers, or here see if you can make a date from the String
            return o;
        }
    }

    private Object handleNumberFormatting(Object o) {
        if (Number.class.isInstance(o)) {
            Number number = (Number) o;
            return numberFormat.format(number);
        } else {
            // TODO: Either split input and output fetchers, or here see if you can make a number from the String
            return o;
        }
    }
}
