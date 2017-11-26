/*
 * Copyright 2002-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.imasahiro.spring;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.net.URLConnection;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.context.MessageSource;
import org.springframework.context.support.AbstractResourceBasedMessageSource;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.util.ClassUtils;

/**
 * {@link MessageSource} implementation that
 * accesses resource bundles using specified basenames. This class relies
 * on the underlying JDK's {@link ResourceBundle} implementation,
 * in combination with the JDK's standard message parsing provided by
 * {@link MessageFormat}.
 *
 * <p>This MessageSource caches both the accessed ResourceBundle instances and
 * the generated MessageFormats for each message. It also implements rendering of
 * no-arg messages without MessageFormat, as supported by the AbstractMessageSource
 * base class. The caching provided by this MessageSource is significantly faster
 * than the built-in caching of the {@code java.util.ResourceBundle} class.
 *
 * <p>The basenames follow {@link ResourceBundle} conventions: essentially,
 * a fully-qualified classpath location. If it doesn't contain a package qualifier
 * (such as {@code org.mypackage}), it will be resolved from the classpath root.
 * Note that the JDK's standard ResourceBundle treats dots as package separators:
 * This means that "test.theme" is effectively equivalent to "test/theme".
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @see #setBasenames
 * @see ReloadableResourceBundleMessageSource
 * @see ResourceBundle
 * @see MessageFormat
 */
public class ConcurrentResourceBundleMessageSource extends AbstractResourceBasedMessageSource
        implements BeanClassLoaderAware {

    private ClassLoader bundleClassLoader;

    private ClassLoader beanClassLoader = ClassUtils.getDefaultClassLoader();

    /**
     * Cache to hold loaded ResourceBundles.
     * This Map is keyed with the bundle basename, which holds a Map that is
     * keyed with the Locale and in turn holds the ResourceBundle instances.
     * This allows for very efficient hash lookups, significantly faster
     * than the ResourceBundle class's own cache.
     */
    private final Map<String, Map<Locale, ResourceBundle>> cachedResourceBundles =
            new ConcurrentHashMap<>();

    /**
     * Cache to hold already generated MessageFormats.
     * This Map is keyed with the ResourceBundle, which holds a Map that is
     * keyed with the message code, which in turn holds a Map that is keyed
     * with the Locale and holds the MessageFormat values. This allows for
     * very efficient hash lookups without concatenated keys.
     * @see #getMessageFormat
     */
    private final Map<ResourceBundle, Map<String, Map<Locale, MessageFormat>>> cachedBundleMessageFormats =
            new ConcurrentHashMap<>();

    /**
     * Set the ClassLoader to load resource bundles with.
     *
     * <p>Default is the containing BeanFactory's
     * {@link BeanClassLoaderAware bean ClassLoader},
     * or the default ClassLoader determined by
     * {@link ClassUtils#getDefaultClassLoader()}
     * if not running within a BeanFactory.
     */
    private void setBundleClassLoader(ClassLoader classLoader) {
        bundleClassLoader = classLoader;
    }

    /**
     * Return the ClassLoader to load resource bundles with.
     *
     * <p>Default is the containing BeanFactory's bean ClassLoader.
     * @see #setBundleClassLoader
     */
    private ClassLoader getBundleClassLoader() {
        return bundleClassLoader != null ? bundleClassLoader : beanClassLoader;
    }

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        beanClassLoader = classLoader != null ? classLoader : ClassUtils.getDefaultClassLoader();
    }

    /**
     * Resolves the given message code as key in the registered resource bundles,
     * returning the value found in the bundle as-is (without MessageFormat parsing).
     */
    @Override
    protected String resolveCodeWithoutArguments(String code, Locale locale) {
        Set<String> basenames = getBasenameSet();
        for (String basename : basenames) {
            ResourceBundle bundle = getResourceBundle(basename, locale);
            if (bundle != null) {
                String result = getStringOrNull(bundle, code);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    /**
     * Resolves the given message code as key in the registered resource bundles,
     * using a cached MessageFormat instance per message code.
     */
    @Override
    protected MessageFormat resolveCode(String code, Locale locale) {
        Set<String> basenames = getBasenameSet();
        for (String basename : basenames) {
            ResourceBundle bundle = getResourceBundle(basename, locale);
            if (bundle != null) {
                MessageFormat messageFormat = getMessageFormat(bundle, code, locale);
                if (messageFormat != null) {
                    return messageFormat;
                }
            }
        }
        return null;
    }

    /**
     * Return a ResourceBundle for the given basename and code,
     * fetching already generated MessageFormats from the cache.
     *
     * @param basename the basename of the ResourceBundle
     * @param locale the Locale to find the ResourceBundle for
     *
     * @return the resulting ResourceBundle, or {@code null} if none
     *         found for the given basename and Locale
     */
    private ResourceBundle getResourceBundle(String basename, Locale locale) {
        if (getCacheMillis() >= 0) {
            // Fresh ResourceBundle.getBundle call in order to let ResourceBundle
            // do its native caching, at the expense of more extensive lookup steps.
            return doGetBundle(basename, locale);
        } else {
            // Cache forever: prefer locale cache over repeated getBundle calls.
            Map<Locale, ResourceBundle> localeMap = this.cachedResourceBundles.get(basename);
            if (localeMap != null) {
                ResourceBundle bundle = localeMap.get(locale);
                if (bundle != null) {
                    return bundle;
                }
            }
            try {
                ResourceBundle bundle = doGetBundle(basename, locale);
                if (localeMap == null) {
                    localeMap = new ConcurrentHashMap<>();
                    Map<Locale, ResourceBundle> existing = cachedResourceBundles.putIfAbsent(
                            basename, localeMap);
                    if (existing != null) {
                        localeMap = existing;
                    }
                }
                localeMap.put(locale, bundle);
                return bundle;
            } catch (MissingResourceException ex) {
                if (logger.isWarnEnabled()) {
                    logger.warn("ResourceBundle [" + basename + "] not found for MessageSource: " + ex
                            .getMessage());
                }
                // Assume bundle not found
                // -> do NOT throw the exception to allow for checking parent message source.
                return null;
            }
        }
    }

    /**
     * Obtain the resource bundle for the given basename and Locale.
     *
     * @param basename the basename to look for
     * @param locale the Locale to look for
     *
     * @return the corresponding ResourceBundle
     *
     * @throws MissingResourceException if no matching bundle could be found
     * @see ResourceBundle#getBundle(String, Locale, ClassLoader)
     * @see #getBundleClassLoader()
     */
    private ResourceBundle doGetBundle(String basename, Locale locale) throws MissingResourceException {
        return ResourceBundle.getBundle(basename, locale, getBundleClassLoader(), new MessageSourceControl());
    }

    /**
     * Load a property-based resource bundle from the given reader.
     *
     * <p>The default implementation returns a {@link PropertyResourceBundle}.
     *
     * @param reader the reader for the target resource
     *
     * @return the fully loaded bundle
     *
     * @throws IOException in case of I/O failure
     * @see PropertyResourceBundle#PropertyResourceBundle(Reader)
     * @since 4.2
     */
    private ResourceBundle loadBundle(Reader reader) throws IOException {
        return new PropertyResourceBundle(reader);
    }

    /**
     * Return a MessageFormat for the given bundle and code,
     * fetching already generated MessageFormats from the cache.
     *
     * @param bundle the ResourceBundle to work on
     * @param code the message code to retrieve
     * @param locale the Locale to use to build the MessageFormat
     *
     * @return the resulting MessageFormat, or {@code null} if no message
     *         defined for the given code
     *
     * @throws MissingResourceException if thrown by the ResourceBundle
     */
    private MessageFormat getMessageFormat(ResourceBundle bundle, String code, Locale locale)
            throws MissingResourceException {
        Map<String, Map<Locale, MessageFormat>> codeMap = this.cachedBundleMessageFormats.get(bundle);
        Map<Locale, MessageFormat> localeMap = null;
        if (codeMap != null) {
            localeMap = codeMap.get(code);
            if (localeMap != null) {
                MessageFormat result = localeMap.get(locale);
                if (result != null) {
                    return result;
                }
            }
        }

        String msg = getStringOrNull(bundle, code);
        if (msg != null) {
            if (codeMap == null) {
                codeMap = new ConcurrentHashMap<>();
                Map<String, Map<Locale, MessageFormat>> existing =
                        this.cachedBundleMessageFormats.putIfAbsent(bundle, codeMap);
                if (existing != null) {
                    codeMap = existing;
                }
            }
            if (localeMap == null) {
                localeMap = new ConcurrentHashMap<>();
                Map<Locale, MessageFormat> existing = codeMap.putIfAbsent(code, localeMap);
                if (existing != null) {
                    localeMap = existing;
                }
            }
            MessageFormat result = createMessageFormat(msg, locale);
            localeMap.put(locale, result);
            return result;
        }

        return null;
    }

    /**
     * Efficiently retrieve the String value for the specified key,
     * or return {@code null} if not found.
     *
     * <p>As of 4.2, the default implementation checks {@code containsKey}
     * before it attempts to call {@code getString} (which would require
     * catching {@code MissingResourceException} for key not found).
     *
     * <p>Can be overridden in subclasses.
     *
     * @param bundle the ResourceBundle to perform the lookup in
     * @param key the key to look up
     *
     * @return the associated value, or {@code null} if none
     *
     * @see ResourceBundle#getString(String)
     * @see ResourceBundle#containsKey(String)
     * @since 4.2
     */
    private String getStringOrNull(ResourceBundle bundle, String key) {
        if (bundle.containsKey(key)) {
            try {
                return bundle.getString(key);
            } catch (MissingResourceException ex) {
                // Assume key not found for some other reason
                // -> do NOT throw the exception to allow for checking parent message source.
            }
        }
        return null;
    }

    /**
     * Show the configuration of this MessageSource.
     */
    @Override
    public String toString() {
        return getClass().getName() + ": basenames=" + getBasenameSet();
    }

    /**
     * Custom implementation of Java 6's {@code ResourceBundle.Control},
     * adding support for custom file encodings, deactivating the fallback to the
     * system locale and activating ResourceBundle's native cache, if desired.
     */
    private class MessageSourceControl extends ResourceBundle.Control {

        @Override
        public ResourceBundle newBundle(String baseName, Locale locale, String format, ClassLoader loader,
                                        boolean reload)
                throws IllegalAccessException, InstantiationException, IOException {

            // Special handling of default encoding
            if ("java.properties".equals(format)) {
                String bundleName = toBundleName(baseName, locale);
                final String resourceName = toResourceName(bundleName, "properties");
                final ClassLoader classLoader = loader;
                final boolean reloadFlag = reload;
                InputStream stream;
                try {
                    stream = AccessController.doPrivileged(
                            (PrivilegedExceptionAction<InputStream>) () -> {
                                InputStream is = null;
                                if (reloadFlag) {
                                    URL url = classLoader.getResource(resourceName);
                                    if (url != null) {
                                        URLConnection connection = url.openConnection();
                                        if (connection != null) {
                                            connection.setUseCaches(false);
                                            is = connection.getInputStream();
                                        }
                                    }
                                } else {
                                    is = classLoader.getResourceAsStream(resourceName);
                                }
                                return is;
                            });
                } catch (PrivilegedActionException ex) {
                    throw (IOException) ex.getException();
                }
                if (stream != null) {
                    String encoding = getDefaultEncoding();
                    if (encoding == null) {
                        encoding = "ISO-8859-1";
                    }
                    try {
                        return loadBundle(new InputStreamReader(stream, encoding));
                    } finally {
                        stream.close();
                    }
                } else {
                    return null;
                }
            } else {
                // Delegate handling of "java.class" format to standard Control
                return super.newBundle(baseName, locale, format, loader, reload);
            }
        }

        @Override
        public Locale getFallbackLocale(String baseName, Locale locale) {
            return isFallbackToSystemLocale() ? super.getFallbackLocale(baseName, locale) : null;
        }

        @Override
        public long getTimeToLive(String baseName, Locale locale) {
            long cacheMillis = getCacheMillis();
            return cacheMillis >= 0 ? cacheMillis : super.getTimeToLive(baseName, locale);
        }

        @Override
        public boolean needsReload(String baseName, Locale locale, String format, ClassLoader loader,
                                   ResourceBundle bundle, long loadTime) {
            if (super.needsReload(baseName, locale, format, loader, bundle, loadTime)) {
                cachedBundleMessageFormats.remove(bundle);
                return true;
            } else {
                return false;
            }
        }
    }
}