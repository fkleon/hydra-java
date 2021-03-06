/*
 * Copyright (c) 2014. Escalon System-Entwicklung, Dietrich Schulten
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for
 * the specific language governing permissions and limitations under the License.
 */
package de.escalon.hypermedia.hydra.serialize;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.impl.BeanAsArraySerializer;
import com.fasterxml.jackson.databind.ser.impl.ObjectIdWriter;
import com.fasterxml.jackson.databind.ser.std.BeanSerializerBase;
import com.fasterxml.jackson.databind.util.NameTransformer;
import de.escalon.hypermedia.hydra.mapping.Expose;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

import static de.escalon.hypermedia.AnnotationUtils.findAnnotation;

public class JacksonHydraSerializer extends BeanSerializerBase {

    public static final String KEY_LD_CONTEXT = "de.escalon.hypermedia.ld-context";

    protected LdContextFactory ldContextFactory;
    private ProxyUnwrapper proxyUnwrapper;

    public JacksonHydraSerializer(BeanSerializerBase source) {
        this(source, (ProxyUnwrapper) null);
    }

    /**
     * Creates new serializer with optional proxy unwrapper.
     *
     * @param source
     *         wrapped serializer
     * @param proxyUnwrapper
     *         to unwrap proxified beans, may be null
     */
    public JacksonHydraSerializer(BeanSerializerBase source, ProxyUnwrapper proxyUnwrapper) {
        super(source);
        this.proxyUnwrapper = proxyUnwrapper;
        this.ldContextFactory = new LdContextFactory();
        ldContextFactory.setProxyUnwrapper(proxyUnwrapper);
    }


    public JacksonHydraSerializer(BeanSerializerBase source,
                                  ObjectIdWriter objectIdWriter) {
        super(source, objectIdWriter);
    }

    public JacksonHydraSerializer(BeanSerializerBase source,
                                  String[] toIgnore) {
        super(source, toIgnore);
    }

    public BeanSerializerBase withObjectIdWriter(
            ObjectIdWriter objectIdWriter) {
        return new JacksonHydraSerializer(this, objectIdWriter);
    }

    protected BeanSerializerBase withIgnorals(String[] toIgnore) {
        return new JacksonHydraSerializer(this, toIgnore);
    }

    @Override
    protected BeanSerializerBase asArraySerializer() {
    /* Can not:
     *
     * - have Object Id (may be allowed in future)
     * - have any getter
     *
     */
        if ((_objectIdWriter == null)
                && (_anyGetterWriter == null)
                && (_propertyFilterId == null)
                ) {
            return new BeanAsArraySerializer(this);
        }
        // already is one, so:
        return this;
    }

    @Override
    protected BeanSerializerBase withFilterId(Object filterId) {
        final JacksonHydraSerializer ret = new JacksonHydraSerializer(this);
        ret.withFilterId(filterId);
        return ret;
    }

    @Override
    public void serialize(Object bean, JsonGenerator jgen,
                          SerializerProvider serializerProvider) throws IOException {
        if (!isUnwrappingSerializer()) {
            jgen.writeStartObject();
        }
        Deque<LdContext> contextStack = (Deque<LdContext>) serializerProvider.getAttribute(KEY_LD_CONTEXT);
        if (contextStack == null) {
            contextStack = new ArrayDeque<LdContext>();
            serializerProvider.setAttribute(KEY_LD_CONTEXT, contextStack);
        }

        serializeContext(bean, jgen, serializerProvider, contextStack);
        serializeType(bean, jgen, serializerProvider);
        serializeFields(bean, jgen, serializerProvider);
        if (!isUnwrappingSerializer()) {
            jgen.writeEndObject();
        }
        contextStack = (Deque<LdContext>) serializerProvider.getAttribute(KEY_LD_CONTEXT);
        if (!contextStack.isEmpty()) {
            contextStack.pop();
        }
    }

    protected void serializeType(Object bean, JsonGenerator jgen, SerializerProvider provider) throws IOException {
        if (proxyUnwrapper != null) {
            bean = proxyUnwrapper.unwrapProxy(bean);
        }
        // adds @type attribute, reflecting the simple name of the class or the exposed annotation on the class.
        final Expose classExpose = findAnnotation(bean.getClass(), Expose.class);
        // TODO allow to search up the hierarchy for ResourceSupport mixins and cache found result?
        final Class<?> mixin = provider.getConfig()
                .findMixInClassFor(bean.getClass());
        final Expose mixinExpose = findAnnotation(mixin, Expose.class);
        final String val;
        if (mixinExpose != null) {
            val = mixinExpose.value(); // mixin wins over class
        } else if (classExpose != null) {
            val = classExpose.value(); // expose is better than Java type
        } else {

            val = bean.getClass()
                    .getSimpleName();
        }

        jgen.writeStringField(JsonLdKeywords.AT_TYPE, val);
    }

    protected void serializeContext(Object bean, JsonGenerator jgen,
                                    SerializerProvider serializerProvider, Deque<LdContext> contextStack)
            throws IOException {

        // TODO: this code is duplicated in PagedResourcesSerializer!!!
        // couldn't inherit from this because this is a serializer wrapper
        // make it a static utility or a common collaborator dependency?

        if (proxyUnwrapper != null) {
            bean = proxyUnwrapper.unwrapProxy(bean);
        }
        MixinSource mixinSource = new JacksonMixinSource(serializerProvider.getConfig());
        final Class<?> mixInClass = mixinSource.findMixInClassFor(bean.getClass());

        final LdContext parentContext = contextStack.peek();
        LdContext currentContext = new LdContext(parentContext, ldContextFactory.getVocab(mixinSource, bean,
                mixInClass), ldContextFactory.getTerms(mixinSource, bean, mixInClass));
        contextStack.push(currentContext);
        // check if we need to write a context for the current bean at all
        // If it is in the same vocab: no context
        // If the terms are already defined in the context: no context
        boolean mustWriteContext;
        if (parentContext == null || !parentContext.contains(currentContext)) {
            mustWriteContext = true;
        } else {
            mustWriteContext = false;
        }

        if (mustWriteContext) {
            // begin context
            // default context: schema.org vocab or vocab package annotation
            jgen.writeObjectFieldStart("@context");
            // do not repeat vocab if already defined in current context
            if (parentContext == null || parentContext.vocab == null ||
                    (currentContext.vocab != null && !currentContext.vocab.equals(parentContext.vocab))) {
                jgen.writeStringField(JsonLdKeywords.AT_VOCAB, currentContext.vocab);
            }

            for (Map.Entry<String, Object> termEntry : currentContext.terms.entrySet()) {
                if (termEntry.getValue() instanceof String) {
                    jgen.writeStringField(termEntry.getKey(), termEntry.getValue()
                            .toString());
                } else {
                    jgen.writeObjectField(termEntry.getKey(), termEntry.getValue());
                }
            }
            jgen.writeEndObject();
            // end context
        }
    }

    @Override
    public JsonSerializer<Object> unwrappingSerializer(NameTransformer unwrapper) {
        UnwrappingJacksonHydraSerializer unwrappingJacksonHydraSerializer = new UnwrappingJacksonHydraSerializer
                (this, proxyUnwrapper);
        return unwrappingJacksonHydraSerializer;
    }

    @Override
    public void resolve(SerializerProvider provider) throws JsonMappingException {
        super.resolve(provider);
    }

    @Override
    public JsonSerializer<?> createContextual(SerializerProvider provider,
                                              BeanProperty property) throws JsonMappingException {
        return super.createContextual(provider, property);
    }
}
