//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.servlet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.EventListener;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.RequestDispatcher;
import javax.servlet.Servlet;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRegistration;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.ServletSecurityElement;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.pathmap.MappedResource;
import org.eclipse.jetty.http.pathmap.PathMappings;
import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.http.pathmap.ServletPathSpec;
import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.ServletPathMapping;
import org.eclipse.jetty.server.ServletRequestHttpWrapper;
import org.eclipse.jetty.server.ServletResponseHttpWrapper;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ScopedHandler;
import org.eclipse.jetty.util.ArrayUtil;
import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.MultiException;
import org.eclipse.jetty.util.MultiMap;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.component.LifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Servlet HttpHandler.
 * <p>
 * This handler maps requests to servlets that implement the
 * javax.servlet.http.HttpServlet API.
 * <P>
 * This handler does not implement the full J2EE features and is intended to
 * be used directly when a full web application is not required.  If a Web application is required,
 * then this handler should be used as part of a <code>org.eclipse.jetty.webapp.WebAppContext</code>.
 * <p>
 * Unless run as part of a {@link ServletContextHandler} or derivative, the {@link #initialize()}
 * method must be called manually after start().
 */
@ManagedObject("Servlet Handler")
public class ServletHandler extends ScopedHandler
{
    private static final Logger LOG = LoggerFactory.getLogger(ServletHandler.class);

    private ServletContextHandler _contextHandler;
    private ServletContext _servletContext;
    private FilterHolder[] _filters = new FilterHolder[0];
    private FilterMapping[] _filterMappings;
    private int _matchBeforeIndex = -1; //index of last programmatic FilterMapping with isMatchAfter=false
    private int _matchAfterIndex = -1;  //index of 1st programmatic FilterMapping with isMatchAfter=true
    private boolean _filterChainsCached = true;
    private int _maxFilterChainsCacheSize = 512;
    private boolean _startWithUnavailable = false;
    private boolean _ensureDefaultServlet = true;
    private IdentityService _identityService;
    private boolean _allowDuplicateMappings = false;

    private ServletHolder[] _servlets = new ServletHolder[0];
    private ServletMapping[] _servletMappings;
    private final Map<String, FilterHolder> _filterNameMap = new HashMap<>();
    private List<FilterMapping> _filterPathMappings;
    private MultiMap<FilterMapping> _filterNameMappings;

    private final Map<String, MappedServlet> _servletNameMap = new HashMap<>();
    private PathMappings<MappedServlet> _servletPathMap;

    private ListenerHolder[] _listeners = new ListenerHolder[0];
    private boolean _initialized = false;

    @SuppressWarnings("unchecked")
    protected final ConcurrentMap<String, FilterChain>[] _chainCache = new ConcurrentMap[FilterMapping.ALL];

    @SuppressWarnings("unchecked")
    protected final Queue<String>[] _chainLRU = new Queue[FilterMapping.ALL];

    /**
     * Constructor.
     */
    public ServletHandler()
    {
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        dumpObjects(out, indent,
            DumpableCollection.fromArray("listeners " + this, _listeners),
            DumpableCollection.fromArray("filters " + this, _filters),
            DumpableCollection.fromArray("filterMappings " + this, _filterMappings),
            DumpableCollection.fromArray("servlets " + this, _servlets),
            DumpableCollection.fromArray("servletMappings " + this, _servletMappings));
    }

    @Override
    protected synchronized void doStart()
        throws Exception
    {
        ContextHandler.Context context = ContextHandler.getCurrentContext();
        _servletContext = context == null ? new ContextHandler.StaticContext() : context;
        _contextHandler = (ServletContextHandler)(context == null ? null : context.getContextHandler());

        if (_contextHandler != null)
        {
            SecurityHandler securityHandler = _contextHandler.getChildHandlerByClass(SecurityHandler.class);
            if (securityHandler != null)
                _identityService = securityHandler.getIdentityService();
        }

        updateNameMappings();
        updateMappings();

        if (getServletMapping("/") == null && isEnsureDefaultServlet())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Adding Default404Servlet to {}", this);
            addServletWithMapping(Default404Servlet.class, "/");
            updateMappings();
            getServletMapping("/").setFromDefaultDescriptor(true);
        }

        if (isFilterChainsCached())
        {
            _chainCache[FilterMapping.REQUEST] = new ConcurrentHashMap<>();
            _chainCache[FilterMapping.FORWARD] = new ConcurrentHashMap<>();
            _chainCache[FilterMapping.INCLUDE] = new ConcurrentHashMap<>();
            _chainCache[FilterMapping.ERROR] = new ConcurrentHashMap<>();
            _chainCache[FilterMapping.ASYNC] = new ConcurrentHashMap<>();

            _chainLRU[FilterMapping.REQUEST] = new ConcurrentLinkedQueue<>();
            _chainLRU[FilterMapping.FORWARD] = new ConcurrentLinkedQueue<>();
            _chainLRU[FilterMapping.INCLUDE] = new ConcurrentLinkedQueue<>();
            _chainLRU[FilterMapping.ERROR] = new ConcurrentLinkedQueue<>();
            _chainLRU[FilterMapping.ASYNC] = new ConcurrentLinkedQueue<>();
        }

        if (_contextHandler == null)
            initialize();

        super.doStart();
    }

    /**
     * @return true if ServletHandler always has a default servlet, using {@link Default404Servlet} if no other
     * default servlet is configured.
     */
    public boolean isEnsureDefaultServlet()
    {
        return _ensureDefaultServlet;
    }

    /**
     * @param ensureDefaultServlet true if ServletHandler always has a default servlet, using {@link Default404Servlet} if no other
     * default servlet is configured.
     */
    public void setEnsureDefaultServlet(boolean ensureDefaultServlet)
    {
        _ensureDefaultServlet = ensureDefaultServlet;
    }

    @Override
    protected void start(LifeCycle l) throws Exception
    {
        //Don't start the whole object tree (ie all the servlet and filter Holders) when
        //this handler starts. They have a slightly special lifecycle, and should only be
        //started AFTER the handlers have all started (and the ContextHandler has called
        //the context listeners).
        if (!(l instanceof Holder))
            super.start(l);
    }

    @Override
    protected synchronized void doStop()
        throws Exception
    {
        super.doStop();

        // Stop filters
        List<FilterHolder> filterHolders = new ArrayList<>();
        List<FilterMapping> filterMappings = ArrayUtil.asMutableList(_filterMappings);
        if (_filters != null)
        {
            for (int i = _filters.length; i-- > 0; )
            {
                FilterHolder filter = _filters[i];
                try
                {
                    filter.stop();
                }
                catch (Exception e)
                {
                    LOG.warn("Unable to stop filter {}", filter, e);
                }
                if (filter.getSource() != Source.EMBEDDED)
                {
                    //remove all of the mappings that were for non-embedded filters
                    _filterNameMap.remove(filter.getName());
                    //remove any mappings associated with this filter
                    filterMappings.removeIf(fm -> fm.getFilterName().equals(filter.getName()));
                }
                else
                    filterHolders.add(filter); //only retain embedded
            }
        }

        //Retain only filters and mappings that were added using jetty api (ie Source.EMBEDDED)
        _filters = (FilterHolder[])LazyList.toArray(filterHolders, FilterHolder.class);
        _filterMappings = (FilterMapping[])LazyList.toArray(filterMappings, FilterMapping.class);

        _matchAfterIndex = (_filterMappings == null || _filterMappings.length == 0 ? -1 : _filterMappings.length - 1);
        _matchBeforeIndex = -1;

        // Stop servlets
        List<ServletHolder> servletHolders = new ArrayList<>();  //will be remaining servlets
        List<ServletMapping> servletMappings = ArrayUtil.asMutableList(_servletMappings); //will be remaining mappings
        if (_servlets != null)
        {
            for (int i = _servlets.length; i-- > 0; )
            {
                ServletHolder servlet = _servlets[i];
                try
                {
                    servlet.stop();
                }
                catch (Exception e)
                {
                    LOG.warn("Unable to stop servlet {}", servlet, e);
                }

                if (servlet.getSource() != Source.EMBEDDED)
                {
                    //remove from servlet name map
                    _servletNameMap.remove(servlet.getName());
                    //remove any mappings associated with this servlet
                    servletMappings.removeIf(sm -> sm.getServletName().equals(servlet.getName()));
                }
                else
                    servletHolders.add(servlet); //only retain embedded
            }
        }

        //Retain only Servlets and mappings added via jetty apis (ie Source.EMBEDDED)
        _servlets = (ServletHolder[])LazyList.toArray(servletHolders, ServletHolder.class);
        _servletMappings = (ServletMapping[])LazyList.toArray(servletMappings, ServletMapping.class);
        
        if (_contextHandler != null)
            _contextHandler.contextDestroyed();

        //Retain only Listeners added via jetty apis (is Source.EMBEDDED)
        List<ListenerHolder> listenerHolders = new ArrayList<>();
        if (_listeners != null)
        {
            for (int i = _listeners.length; i-- > 0; )
            {
                ListenerHolder listener = _listeners[i];
                try
                {
                    listener.stop();
                }
                catch (Exception e)
                {
                    LOG.warn("Unable to stop listener {}", listener, e);
                }
                if (listener.getSource() == Source.EMBEDDED)
                    listenerHolders.add(listener);
            }
        }
        _listeners = (ListenerHolder[])LazyList.toArray(listenerHolders, ListenerHolder.class);

        //will be regenerated on next start
        _filterPathMappings = null;
        _filterNameMappings = null;
        _servletPathMap = null;
        _initialized = false;
    }

    protected IdentityService getIdentityService()
    {
        return _identityService;
    }

    @ManagedAttribute(value = "filters", readonly = true)
    public FilterMapping[] getFilterMappings()
    {
        return _filterMappings;
    }

    @ManagedAttribute(value = "filters", readonly = true)
    public FilterHolder[] getFilters()
    {
        return _filters;
    }

    public ServletContext getServletContext()
    {
        return _servletContext;
    }

    @ManagedAttribute(value = "mappings of servlets", readonly = true)
    public ServletMapping[] getServletMappings()
    {
        return _servletMappings;
    }

    /**
     * Get the ServletMapping matching the path
     *
     * @param pathSpec the path spec
     * @return the servlet mapping for the path spec (or null if not found)
     */
    public ServletMapping getServletMapping(String pathSpec)
    {
        if (pathSpec == null || _servletMappings == null)
            return null;

        ServletMapping mapping = null;
        for (int i = 0; i < _servletMappings.length && mapping == null; i++)
        {
            ServletMapping m = _servletMappings[i];
            if (m.getPathSpecs() != null)
            {
                for (String p : m.getPathSpecs())
                {
                    if (pathSpec.equals(p))
                    {
                        mapping = m;
                        break;
                    }
                }
            }
        }
        return mapping;
    }

    @ManagedAttribute(value = "servlets", readonly = true)
    public ServletHolder[] getServlets()
    {
        return _servlets;
    }

    public List<ServletHolder> getServlets(Class<?> clazz)
    {
        List<ServletHolder> holders = null;
        for (ServletHolder holder : _servlets)
        {
            Class<? extends Servlet> held = holder.getHeldClass();
            if ((held == null && holder.getClassName() != null && holder.getClassName().equals(clazz.getName())) ||
                (held != null && clazz.isAssignableFrom(holder.getHeldClass())))
            {
                if (holders == null)
                    holders = new ArrayList<>();
                holders.add(holder);
            }
        }
        return holders == null ? Collections.emptyList() : holders;
    }

    public ServletHolder getServlet(String name)
    {
        MappedServlet mapped = _servletNameMap.get(name);
        if (mapped != null)
            return mapped.getServletHolder();
        return null;
    }

    @Override
    public void doScope(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        // Get the base requests
        final ServletPathMapping old_servlet_path_mapping = baseRequest.getServletPathMapping();

        DispatcherType type = baseRequest.getDispatcherType();

        ServletHolder servletHolder = null;
        UserIdentity.Scope oldScope = null;

        MappedServlet mappedServlet = getMappedServlet(target);
        if (mappedServlet != null)
        {
            servletHolder = mappedServlet.getServletHolder();
            ServletPathMapping servletPathMapping = mappedServlet.getServletPathMapping(target);
            if (servletPathMapping != null)
            {
                // Setting the servletPathMapping also provides the servletPath and pathInfo
                if (DispatcherType.INCLUDE.equals(type))
                    baseRequest.setAttribute(RequestDispatcher.INCLUDE_MAPPING, servletPathMapping);
                else
                    baseRequest.setServletPathMapping(servletPathMapping);
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("servlet {}|{}|{}|{} -> {}", baseRequest.getContextPath(), baseRequest.getServletPath(), baseRequest.getPathInfo(), baseRequest.getHttpServletMapping(), servletHolder);

        try
        {
            // Do the filter/handling thang
            oldScope = baseRequest.getUserIdentityScope();
            baseRequest.setUserIdentityScope(servletHolder);

            nextScope(target, baseRequest, request, response);
        }
        finally
        {
            if (oldScope != null)
                baseRequest.setUserIdentityScope(oldScope);

            if (!(DispatcherType.INCLUDE.equals(type)))
                baseRequest.setServletPathMapping(old_servlet_path_mapping);
        }
    }

    @Override
    public void doHandle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
        throws IOException, ServletException
    {
        ServletHolder servletHolder = (ServletHolder)baseRequest.getUserIdentityScope();
        FilterChain chain = null;

        // find the servlet
        if (target.startsWith("/"))
        {
            if (servletHolder != null && _filterMappings != null && _filterMappings.length > 0)
                chain = getFilterChain(baseRequest, target, servletHolder);
        }
        else
        {
            if (servletHolder != null)
            {
                if (_filterMappings != null && _filterMappings.length > 0)
                {
                    chain = getFilterChain(baseRequest, null, servletHolder);
                }
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("chain={}", chain);

        try
        {
            if (servletHolder == null)
                notFound(baseRequest, request, response);
            else
            {
                // unwrap any tunnelling of base Servlet request/responses
                ServletRequest req = request;
                if (req instanceof ServletRequestHttpWrapper)
                    req = ((ServletRequestHttpWrapper)req).getRequest();
                ServletResponse res = response;
                if (res instanceof ServletResponseHttpWrapper)
                    res = ((ServletResponseHttpWrapper)res).getResponse();

                // Do the filter/handling thang
                servletHolder.prepare(baseRequest, req, res);

                if (chain != null)
                    chain.doFilter(req, res);
                else
                    servletHolder.handle(baseRequest, req, res);
            }
        }
        finally
        {
            if (servletHolder != null)
                baseRequest.setHandled(true);
        }
    }

    /**
     * Get MappedServlet for target.
     *
     * @param target Path within _context or servlet name
     * @return MappedServlet matched by path or name.  Named servlets have a null PathSpec
     */
    public MappedServlet getMappedServlet(String target)
    {
        if (target.startsWith("/"))
        {
            if (_servletPathMap == null)
                return null;

            MappedResource<MappedServlet> match = _servletPathMap.getMatch(target);
            if (match == null)
                return null;
            return match.getResource();
        }

        return _servletNameMap.get(target);
    }

    private FilterChain getFilterChain(Request baseRequest, String pathInContext, ServletHolder servletHolder)
    {
        String key = pathInContext == null ? servletHolder.getName() : pathInContext;
        int dispatch = FilterMapping.dispatch(baseRequest.getDispatcherType());

        if (_filterChainsCached)
        {
            FilterChain chain = _chainCache[dispatch].get(key);
            if (chain != null)
                return chain;
        }

        // Build list of filters (list of FilterHolder objects)
        List<FilterHolder> filters = new ArrayList<>();

        // Path filters
        if (pathInContext != null && _filterPathMappings != null)
        {
            for (FilterMapping filterPathMapping : _filterPathMappings)
            {
                if (filterPathMapping.appliesTo(pathInContext, dispatch))
                    filters.add(filterPathMapping.getFilterHolder());
            }
        }

        // Servlet name filters
        if (servletHolder != null && _filterNameMappings != null && !_filterNameMappings.isEmpty())
        {
            Object o = _filterNameMappings.get(servletHolder.getName());

            for (int i = 0; i < LazyList.size(o); i++)
            {
                FilterMapping mapping = LazyList.get(o, i);
                if (mapping.appliesTo(dispatch))
                    filters.add(mapping.getFilterHolder());
            }

            o = _filterNameMappings.get("*");
            for (int i = 0; i < LazyList.size(o); i++)
            {
                FilterMapping mapping = LazyList.get(o, i);
                if (mapping.appliesTo(dispatch))
                    filters.add(mapping.getFilterHolder());
            }
        }

        if (filters.isEmpty())
            return null;

        FilterChain chain = null;
        if (_filterChainsCached)
        {
            chain = newCachedChain(filters, servletHolder);

            final Map<String, FilterChain> cache = _chainCache[dispatch];
            final Queue<String> lru = _chainLRU[dispatch];

            // Do we have too many cached chains?
            while (_maxFilterChainsCacheSize > 0 && cache.size() >= _maxFilterChainsCacheSize)
            {
                // The LRU list is not atomic with the cache map, so be prepared to invalidate if
                // a key is not found to delete.
                // Delete by LRU (where U==created)
                String k = lru.poll();
                if (k == null)
                {
                    cache.clear();
                    break;
                }
                cache.remove(k);
            }

            cache.put(key, chain);
            lru.add(key);
        }
        else
            chain = new Chain(baseRequest, filters, servletHolder);

        return chain;
    }

    protected void invalidateChainsCache()
    {
        if (_chainLRU[FilterMapping.REQUEST] != null)
        {
            _chainLRU[FilterMapping.REQUEST].clear();
            _chainLRU[FilterMapping.FORWARD].clear();
            _chainLRU[FilterMapping.INCLUDE].clear();
            _chainLRU[FilterMapping.ERROR].clear();
            _chainLRU[FilterMapping.ASYNC].clear();

            _chainCache[FilterMapping.REQUEST].clear();
            _chainCache[FilterMapping.FORWARD].clear();
            _chainCache[FilterMapping.INCLUDE].clear();
            _chainCache[FilterMapping.ERROR].clear();
            _chainCache[FilterMapping.ASYNC].clear();
        }
    }

    /**
     * @return true if the handler is started and there are no unavailable servlets
     */
    public boolean isAvailable()
    {
        if (!isStarted())
            return false;
        ServletHolder[] holders = getServlets();
        for (ServletHolder holder : holders)
        {
            if (holder != null && !holder.isAvailable())
                return false;
        }
        return true;
    }

    /**
     * @param start True if this handler will start with unavailable servlets
     */
    public void setStartWithUnavailable(boolean start)
    {
        _startWithUnavailable = start;
    }

    /**
     * @return the allowDuplicateMappings
     */
    public boolean isAllowDuplicateMappings()
    {
        return _allowDuplicateMappings;
    }

    /**
     * @param allowDuplicateMappings the allowDuplicateMappings to set
     */
    public void setAllowDuplicateMappings(boolean allowDuplicateMappings)
    {
        _allowDuplicateMappings = allowDuplicateMappings;
    }

    /**
     * @return True if this handler will start with unavailable servlets
     */
    public boolean isStartWithUnavailable()
    {
        return _startWithUnavailable;
    }

    /**
     * Initialize filters and load-on-startup servlets.
     *
     * @throws Exception if unable to initialize
     */
    public void initialize()
        throws Exception
    {
        MultiException mx = new MultiException();

        Consumer<BaseHolder<?>> c = h ->
        {
            try
            {
                if (!h.isStarted())
                {
                    h.start();
                    h.initialize();
                }
            }
            catch (Throwable e)
            {
                LOG.debug("Unable to start {}", h, e);
                mx.add(e);
            }
        };
        
        //Start the listeners so we can call them
        Arrays.stream(_listeners).forEach(c);
        
        //call listeners contextInitialized
        if (_contextHandler != null)
            _contextHandler.contextInitialized();
        
        //Only set initialized true AFTER the listeners have been called
        _initialized = true;
            
        //Start the filters then the servlets
        Stream.concat(
            Arrays.stream(_filters),
            Arrays.stream(_servlets).sorted())
            .forEach(c);

        mx.ifExceptionThrow();
    }
    
    /**
     * @return true if initialized has been called, false otherwise
     */
    public boolean isInitialized()
    {
        return _initialized;
    }

    /**
     * @return whether the filter chains are cached.
     */
    public boolean isFilterChainsCached()
    {
        return _filterChainsCached;
    }

    /**
     * Add a holder for a listener
     *
     * @param listener the listener for the holder
     */
    public void addListener(ListenerHolder listener)
    {
        if (listener != null)
            setListeners(ArrayUtil.addToArray(getListeners(), listener, ListenerHolder.class));
    }

    public ListenerHolder[] getListeners()
    {
        return _listeners;
    }

    public void setListeners(ListenerHolder[] listeners)
    {
        if (listeners != null)
            for (ListenerHolder holder : listeners)
            {
                holder.setServletHandler(this);
            }

        _listeners = listeners;
    }

    public ListenerHolder newListenerHolder(Source source)
    {
        return new ListenerHolder(source);
    }

    /**
     * Create a new CachedChain
     *
     * @param filters the filter chain to be cached as a collection of {@link FilterHolder}
     * @param servletHolder the servletHolder
     * @return a new {@link CachedChain} instance
     */
    public CachedChain newCachedChain(List<FilterHolder> filters, ServletHolder servletHolder)
    {
        return new CachedChain(filters, servletHolder);
    }

    /**
     * Add a new servlet holder
     *
     * @param source the holder source
     * @return the servlet holder
     */
    public ServletHolder newServletHolder(Source source)
    {
        return new ServletHolder(source);
    }

    /**
     * Convenience method to add a servlet.
     *
     * @param className the class name
     * @param pathSpec the path spec
     * @return The servlet holder.
     */
    public ServletHolder addServletWithMapping(String className, String pathSpec)
    {
        ServletHolder holder = newServletHolder(Source.EMBEDDED);
        holder.setClassName(className);
        addServletWithMapping(holder, pathSpec);
        return holder;
    }

    /**
     * Convenience method to add a servlet.
     *
     * @param servlet the servlet class
     * @param pathSpec the path spec
     * @return The servlet holder.
     */
    public ServletHolder addServletWithMapping(Class<? extends Servlet> servlet, String pathSpec)
    {
        ServletHolder holder = newServletHolder(Source.EMBEDDED);
        holder.setHeldClass(servlet);
        addServletWithMapping(holder, pathSpec);

        return holder;
    }

    /**
     * Convenience method to add a servlet.
     *
     * @param servlet servlet holder to add
     * @param pathSpec servlet mappings for the servletHolder
     */
    public void addServletWithMapping(ServletHolder servlet, String pathSpec)
    {
        ServletHolder[] holders = getServlets();
        if (holders != null)
            holders = holders.clone();

        try
        {
            synchronized (this)
            {
                if (servlet != null && !containsServletHolder(servlet))
                    setServlets(ArrayUtil.addToArray(holders, servlet, ServletHolder.class));
            }

            ServletMapping mapping = new ServletMapping();
            mapping.setServletName(servlet.getName());
            mapping.setPathSpec(pathSpec);
            setServletMappings(ArrayUtil.addToArray(getServletMappings(), mapping, ServletMapping.class));
        }
        catch (RuntimeException e)
        {
            setServlets(holders);
            throw e;
        }
    }

    /**
     * Convenience method to add a pre-constructed ServletHolder.
     *
     * @param holder the servlet holder
     */
    public void addServlet(ServletHolder holder)
    {
        if (holder == null)
            return;

        synchronized (this)
        {
            if (!containsServletHolder(holder))
                setServlets(ArrayUtil.addToArray(getServlets(), holder, ServletHolder.class));
        }
    }

    /**
     * Convenience method to add a pre-constructed ServletMapping.
     *
     * @param mapping the servlet mapping
     */
    public void addServletMapping(ServletMapping mapping)
    {
        setServletMappings(ArrayUtil.addToArray(getServletMappings(), mapping, ServletMapping.class));
    }

    public Set<String> setServletSecurity(ServletRegistration.Dynamic registration, ServletSecurityElement servletSecurityElement)
    {
        if (_contextHandler != null)
        {
            return _contextHandler.setServletSecurity(registration, servletSecurityElement);
        }
        return Collections.emptySet();
    }

    public FilterHolder newFilterHolder(Source source)
    {
        return new FilterHolder(source);
    }

    public FilterHolder getFilter(String name)
    {
        return _filterNameMap.get(name);
    }

    /**
     * Convenience method to add a filter.
     *
     * @param filter class of filter to create
     * @param pathSpec filter mappings for filter
     * @param dispatches see {@link FilterMapping#setDispatches(int)}
     * @return The filter holder.
     */
    public FilterHolder addFilterWithMapping(Class<? extends Filter> filter, String pathSpec, EnumSet<DispatcherType> dispatches)
    {
        FilterHolder holder = newFilterHolder(Source.EMBEDDED);
        holder.setHeldClass(filter);
        addFilterWithMapping(holder, pathSpec, dispatches);

        return holder;
    }

    /**
     * Convenience method to add a filter.
     *
     * @param className of filter
     * @param pathSpec filter mappings for filter
     * @param dispatches see {@link FilterMapping#setDispatches(int)}
     * @return The filter holder.
     */
    public FilterHolder addFilterWithMapping(String className, String pathSpec, EnumSet<DispatcherType> dispatches)
    {
        FilterHolder holder = newFilterHolder(Source.EMBEDDED);
        holder.setClassName(className);

        addFilterWithMapping(holder, pathSpec, dispatches);
        return holder;
    }

    /**
     * Convenience method to add a filter.
     *
     * @param holder filter holder to add
     * @param pathSpec filter mappings for filter
     * @param dispatches see {@link FilterMapping#setDispatches(int)}
     */
    public void addFilterWithMapping(FilterHolder holder, String pathSpec, EnumSet<DispatcherType> dispatches)
    {
        FilterHolder[] holders = getFilters();
        if (holders != null)
            holders = holders.clone();

        try
        {
            synchronized (this)
            {
                if (holder != null && !containsFilterHolder(holder))
                    setFilters(ArrayUtil.addToArray(holders, holder, FilterHolder.class));
            }

            FilterMapping mapping = new FilterMapping();
            mapping.setFilterName(holder.getName());
            mapping.setPathSpec(pathSpec);
            mapping.setDispatcherTypes(dispatches);
            addFilterMapping(mapping);
        }
        catch (Throwable e)
        {
            setFilters(holders);
            throw e;
        }
    }

    /**
     * Convenience method to add a filter.
     *
     * @param filter class of filter to create
     * @param pathSpec filter mappings for filter
     * @param dispatches see {@link FilterMapping#setDispatches(int)}
     * @return The filter holder.
     */
    public FilterHolder addFilterWithMapping(Class<? extends Filter> filter, String pathSpec, int dispatches)
    {
        FilterHolder holder = newFilterHolder(Source.EMBEDDED);
        holder.setHeldClass(filter);
        addFilterWithMapping(holder, pathSpec, dispatches);

        return holder;
    }

    /**
     * Convenience method to add a filter.
     *
     * @param className of filter
     * @param pathSpec filter mappings for filter
     * @param dispatches see {@link FilterMapping#setDispatches(int)}
     * @return The filter holder.
     */
    public FilterHolder addFilterWithMapping(String className, String pathSpec, int dispatches)
    {
        FilterHolder holder = newFilterHolder(Source.EMBEDDED);
        holder.setClassName(className);

        addFilterWithMapping(holder, pathSpec, dispatches);
        return holder;
    }

    /**
     * Convenience method to add a filter.
     *
     * @param holder filter holder to add
     * @param pathSpec filter mappings for filter
     * @param dispatches see {@link FilterMapping#setDispatches(int)}
     */
    public void addFilterWithMapping(FilterHolder holder, String pathSpec, int dispatches)
    {
        FilterHolder[] holders = getFilters();
        if (holders != null)
            holders = holders.clone();

        try
        {
            synchronized (this)
            {
                if (holder != null && !containsFilterHolder(holder))
                    setFilters(ArrayUtil.addToArray(holders, holder, FilterHolder.class));
            }

            FilterMapping mapping = new FilterMapping();
            mapping.setFilterName(holder.getName());
            mapping.setPathSpec(pathSpec);
            mapping.setDispatches(dispatches);
            addFilterMapping(mapping);
        }
        catch (Throwable e)
        {
            setFilters(holders);
            throw e;
        }
    }

    /**
     * Convenience method to add a filter and mapping
     *
     * @param filter the filter holder
     * @param filterMapping the filter mapping
     */
    public void addFilter(FilterHolder filter, FilterMapping filterMapping)
    {
        if (filter != null)
        {
            synchronized (this)
            {
                if (!containsFilterHolder(filter))
                    setFilters(ArrayUtil.addToArray(getFilters(), filter, FilterHolder.class));
            }
        }
        if (filterMapping != null)
            addFilterMapping(filterMapping);
    }

    /**
     * Convenience method to add a preconstructed FilterHolder
     *
     * @param filter the filter holder
     */
    public void addFilter(FilterHolder filter)
    {
        if (filter == null)
            return;

        synchronized (this)
        {
            if (!containsFilterHolder(filter))
                setFilters(ArrayUtil.addToArray(getFilters(), filter, FilterHolder.class));
        }
    }

    /**
     * Convenience method to add a preconstructed FilterMapping
     *
     * @param mapping the filter mapping
     */
    public void addFilterMapping(FilterMapping mapping)
    {
        if (mapping != null)
        {
            Source source = (mapping.getFilterHolder() == null ? null : mapping.getFilterHolder().getSource());
            FilterMapping[] mappings = getFilterMappings();
            if (mappings == null || mappings.length == 0)
            {
                setFilterMappings(insertFilterMapping(mapping, 0, false));
                if (source == Source.JAVAX_API)
                    _matchAfterIndex = 0;
            }
            else
            {
                //there are existing entries. If this is a programmatic filtermapping, it is added at the end of the list.
                //If this is a normal filtermapping, it is inserted after all the other filtermappings (matchBefores and normals), 
                //but before the first matchAfter filtermapping.
                if (Source.JAVAX_API == source)
                {
                    setFilterMappings(insertFilterMapping(mapping, mappings.length - 1, false));
                    if (_matchAfterIndex < 0)
                        _matchAfterIndex = getFilterMappings().length - 1;
                }
                else
                {
                    //insert non-programmatic filter mappings before any matchAfters, if any
                    if (_matchAfterIndex < 0)
                        setFilterMappings(insertFilterMapping(mapping, mappings.length - 1, false));
                    else
                    {
                        FilterMapping[] newMappings = insertFilterMapping(mapping, _matchAfterIndex, true);
                        ++_matchAfterIndex;
                        setFilterMappings(newMappings);
                    }
                }
            }
        }
    }

    /**
     * Convenience method to add a preconstructed FilterMapping
     *
     * @param mapping the filter mapping
     */
    public void prependFilterMapping(FilterMapping mapping)
    {
        if (mapping != null)
        {
            Source source = (mapping.getFilterHolder() == null ? null : mapping.getFilterHolder().getSource());
            FilterMapping[] mappings = getFilterMappings();
            if (mappings == null || mappings.length == 0)
            {
                setFilterMappings(insertFilterMapping(mapping, 0, false));
                if (Source.JAVAX_API == source)
                    _matchBeforeIndex = 0;
            }
            else
            {
                if (Source.JAVAX_API == source)
                {
                    //programmatically defined filter mappings are prepended to mapping list in the order
                    //in which they were defined. In other words, insert this mapping at the tail of the 
                    //programmatically prepended filter mappings, BEFORE the first web.xml defined filter mapping.

                    if (_matchBeforeIndex < 0)
                    {
                        //no programmatically defined prepended filter mappings yet, prepend this one
                        _matchBeforeIndex = 0;
                        FilterMapping[] newMappings = insertFilterMapping(mapping, 0, true);
                        setFilterMappings(newMappings);
                    }
                    else
                    {
                        FilterMapping[] newMappings = insertFilterMapping(mapping, _matchBeforeIndex, false);
                        ++_matchBeforeIndex;
                        setFilterMappings(newMappings);
                    }
                }
                else
                {
                    //non programmatically defined, just prepend to list
                    FilterMapping[] newMappings = insertFilterMapping(mapping, 0, true);
                    setFilterMappings(newMappings);
                }

                //adjust matchAfterIndex ptr to take account of the mapping we just prepended
                if (_matchAfterIndex >= 0)
                    ++_matchAfterIndex;
            }
        }
    }

    /**
     * Insert a filtermapping in the list
     *
     * @param mapping the FilterMapping to add
     * @param pos the position in the existing arry at which to add it
     * @param before if true, insert before  pos, if false insert after it
     * @return the new FilterMappings post-insert
     */
    protected FilterMapping[] insertFilterMapping(FilterMapping mapping, int pos, boolean before)
    {
        if (pos < 0)
            throw new IllegalArgumentException("FilterMapping insertion pos < 0");
        FilterMapping[] mappings = getFilterMappings();

        if (mappings == null || mappings.length == 0)
        {
            return new FilterMapping[]{mapping};
        }
        FilterMapping[] newMappings = new FilterMapping[mappings.length + 1];

        if (before)
        {
            //copy existing filter mappings up to but not including the pos
            System.arraycopy(mappings, 0, newMappings, 0, pos);

            //add in the new mapping
            newMappings[pos] = mapping;

            //copy the old pos mapping and any remaining existing mappings
            System.arraycopy(mappings, pos, newMappings, pos + 1, mappings.length - pos);
        }
        else
        {
            //copy existing filter mappings up to and including the pos
            System.arraycopy(mappings, 0, newMappings, 0, pos + 1);
            //add in the new mapping after the pos
            newMappings[pos + 1] = mapping;

            //copy the remaining existing mappings
            if (mappings.length > pos + 1)
                System.arraycopy(mappings, pos + 1, newMappings, pos + 2, mappings.length - (pos + 1));
        }
        return newMappings;
    }

    protected synchronized void updateNameMappings()
    {
        // update filter name map
        _filterNameMap.clear();
        if (_filters != null)
        {
            for (FilterHolder filter : _filters)
            {
                _filterNameMap.put(filter.getName(), filter);
                filter.setServletHandler(this);
            }
        }

        // Map servlet names to holders
        _servletNameMap.clear();
        if (_servlets != null)
        {
            // update the maps
            for (ServletHolder servlet : _servlets)
            {
                _servletNameMap.put(servlet.getName(), new MappedServlet(null, servlet));
                servlet.setServletHandler(this);
            }
        }
    }

    protected synchronized void updateMappings()
    {
        // update filter mappings
        if (_filterMappings == null)
        {
            _filterPathMappings = null;
            _filterNameMappings = null;
        }
        else
        {
            _filterPathMappings = new ArrayList<>();
            _filterNameMappings = new MultiMap<>();
            for (FilterMapping filtermapping : _filterMappings)
            {
                FilterHolder filterHolder = _filterNameMap.get(filtermapping.getFilterName());
                if (filterHolder == null)
                    throw new IllegalStateException("No filter named " + filtermapping.getFilterName());
                filtermapping.setFilterHolder(filterHolder);
                if (filtermapping.getPathSpecs() != null)
                    _filterPathMappings.add(filtermapping);

                if (filtermapping.getServletNames() != null)
                {
                    String[] names = filtermapping.getServletNames();
                    for (String name : names)
                    {
                        if (name != null)
                            _filterNameMappings.add(name, filtermapping);
                    }
                }
            }
        }

        // Map servlet paths to holders
        if (_servletMappings == null)
        {
            _servletPathMap = null;
        }
        else
        {
            PathMappings<MappedServlet> pm = new PathMappings<>();

            //create a map of paths to set of ServletMappings that define that mapping
            HashMap<String, List<ServletMapping>> sms = new HashMap<>();
            for (ServletMapping servletMapping : _servletMappings)
            {
                String[] pathSpecs = servletMapping.getPathSpecs();
                if (pathSpecs != null)
                {
                    for (String pathSpec : pathSpecs)
                    {
                        List<ServletMapping> mappings = sms.computeIfAbsent(pathSpec, k -> new ArrayList<>());
                        mappings.add(servletMapping);
                    }
                }
            }

            //evaluate path to servlet map based on servlet mappings
            for (String pathSpec : sms.keySet())
            {
                //for each path, look at the mappings where it is referenced
                //if a mapping is for a servlet that is not enabled, skip it
                List<ServletMapping> mappings = sms.get(pathSpec);

                ServletMapping finalMapping = null;
                for (ServletMapping mapping : mappings)
                {
                    //Get servlet associated with the mapping and check it is enabled
                    ServletHolder servletHolder = getServlet(mapping.getServletName());
                    if (servletHolder == null)
                        throw new IllegalStateException("No such servlet: " + mapping.getServletName());
                    //if the servlet related to the mapping is not enabled, skip it from consideration
                    if (!servletHolder.isEnabled())
                        continue;

                    //only accept a default mapping if we don't have any other
                    if (finalMapping == null)
                        finalMapping = mapping;
                    else
                    {
                        //already have a candidate - only accept another one 
                        //if the candidate is a default, or we're allowing duplicate mappings
                        if (finalMapping.isFromDefaultDescriptor())
                            finalMapping = mapping;
                        else if (isAllowDuplicateMappings())
                        {
                            LOG.warn("Multiple servlets map to path {}: {} and {}, choosing {}", pathSpec, finalMapping.getServletName(), mapping.getServletName(), mapping);
                            finalMapping = mapping;
                        }
                        else
                        {
                            //existing candidate isn't a default, if the one we're looking at isn't a default either, then its an error
                            if (!mapping.isFromDefaultDescriptor())
                            {
                                ServletHolder finalMappedServlet = getServlet(finalMapping.getServletName());
                                throw new IllegalStateException("Multiple servlets map to path " +
                                    pathSpec + ": " +
                                    finalMappedServlet.getName() + "[mapped:" + finalMapping.getSource() + "]," +
                                    mapping.getServletName() + "[mapped:" + mapping.getSource() + "]");
                            }
                        }
                    }
                }
                if (finalMapping == null)
                    throw new IllegalStateException("No acceptable servlet mappings for " + pathSpec);

                if (LOG.isDebugEnabled())
                    LOG.debug("Path={}[{}] mapped to servlet={}[{}]",
                        pathSpec,
                        finalMapping.getSource(),
                        finalMapping.getServletName(),
                        getServlet(finalMapping.getServletName()).getSource());

                ServletPathSpec servletPathSpec = new ServletPathSpec(pathSpec);
                MappedServlet mappedServlet = new MappedServlet(servletPathSpec, getServlet(finalMapping.getServletName()));
                pm.put(servletPathSpec, mappedServlet);
            }

            _servletPathMap = pm;
        }

        // flush filter chain cache
        for (int i = _chainCache.length; i-- > 0; )
        {
            if (_chainCache[i] != null)
                _chainCache[i].clear();
        }

        if (LOG.isDebugEnabled())
        {
            LOG.debug("filterNameMap=" + _filterNameMap);
            LOG.debug("pathFilters=" + _filterPathMappings);
            LOG.debug("servletFilterMap=" + _filterNameMappings);
            LOG.debug("servletPathMap=" + _servletPathMap);
            LOG.debug("servletNameMap=" + _servletNameMap);
        }

        try
        {
            if (_contextHandler != null && _contextHandler.isStarted() || _contextHandler == null && isStarted())
                initialize();
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    protected void notFound(Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Not Found {}", request.getRequestURI());
        if (getHandler() != null)
            nextHandle(URIUtil.addPaths(request.getServletPath(), request.getPathInfo()), baseRequest, request, response);
    }

    protected synchronized boolean containsFilterHolder(FilterHolder holder)
    {
        if (_filters == null)
            return false;
        for (FilterHolder f : _filters)
        {
            if (f == holder)
                return true;
        }
        return false;
    }

    protected synchronized boolean containsServletHolder(ServletHolder holder)
    {
        if (_servlets == null)
            return false;
        for (ServletHolder s : _servlets)
        {
            @SuppressWarnings("ReferenceEquality")
            boolean foundServletHolder = (s == holder);
            if (foundServletHolder)
                return true;
        }
        return false;
    }

    /**
     * @param filterChainsCached The filterChainsCached to set.
     */
    public void setFilterChainsCached(boolean filterChainsCached)
    {
        _filterChainsCached = filterChainsCached;
    }

    /**
     * @param filterMappings The filterMappings to set.
     */
    public void setFilterMappings(FilterMapping[] filterMappings)
    {
        _filterMappings = filterMappings;
        if (isStarted())
            updateMappings();
        invalidateChainsCache();
    }

    public synchronized void setFilters(FilterHolder[] holders)
    {
        if (holders != null)
            for (FilterHolder holder : holders)
            {
                holder.setServletHandler(this);
            }

        _filters = holders;
        updateNameMappings();
        invalidateChainsCache();
    }

    /**
     * @param servletMappings The servletMappings to set.
     */
    public void setServletMappings(ServletMapping[] servletMappings)
    {
        _servletMappings = servletMappings;
        if (isStarted())
            updateMappings();
        invalidateChainsCache();
    }

    /**
     * Set Servlets.
     *
     * @param holders Array of servlets to define
     */
    public synchronized void setServlets(ServletHolder[] holders)
    {
        if (holders != null)
            for (ServletHolder holder : holders)
            {
                holder.setServletHandler(this);
            }

        _servlets = holders;
        updateNameMappings();
        invalidateChainsCache();
    }

    protected class CachedChain implements FilterChain
    {
        FilterHolder _filterHolder;
        CachedChain _next;
        ServletHolder _servletHolder;

        /**
         * @param filters list of {@link FilterHolder} objects
         * @param servletHolder the current {@link ServletHolder}
         */
        protected CachedChain(List<FilterHolder> filters, ServletHolder servletHolder)
        {
            if (!filters.isEmpty())
            {
                _filterHolder = filters.get(0);
                filters.remove(0);
                _next = new CachedChain(filters, servletHolder);
            }
            else
                _servletHolder = servletHolder;
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response)
            throws IOException, ServletException
        {
            final Request baseRequest = Request.getBaseRequest(request);

            // pass to next filter
            if (_filterHolder != null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("call filter {}", _filterHolder);
                Filter filter = _filterHolder.getFilter();

                //if the request already does not support async, then the setting for the filter
                //is irrelevant. However if the request supports async but this filter does not
                //temporarily turn it off for the execution of the filter
                if (baseRequest.isAsyncSupported() && !_filterHolder.isAsyncSupported())
                {
                    try
                    {
                        baseRequest.setAsyncSupported(false, _filterHolder.toString());
                        filter.doFilter(request, response, _next);
                    }
                    finally
                    {
                        baseRequest.setAsyncSupported(true, null);
                    }
                }
                else
                    filter.doFilter(request, response, _next);

                return;
            }

            // Call servlet
            HttpServletRequest srequest = (HttpServletRequest)request;
            if (_servletHolder == null)
                notFound(baseRequest, srequest, (HttpServletResponse)response);
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("call servlet " + _servletHolder);
                _servletHolder.handle(baseRequest, request, response);
            }
        }

        @Override
        public String toString()
        {
            if (_filterHolder != null)
                return _filterHolder + "->" + _next.toString();
            if (_servletHolder != null)
                return _servletHolder.toString();
            return "null";
        }
    }

    private class Chain implements FilterChain
    {
        final Request _baseRequest;
        final List<FilterHolder> _chain;
        final ServletHolder _servletHolder;
        int _filter = 0;

        private Chain(Request baseRequest, List<FilterHolder> filters, ServletHolder servletHolder)
        {
            _baseRequest = baseRequest;
            _chain = filters;
            _servletHolder = servletHolder;
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response)
            throws IOException, ServletException
        {
            if (LOG.isDebugEnabled())
                LOG.debug("doFilter " + _filter);

            // pass to next filter
            if (_filter < _chain.size())
            {
                FilterHolder holder = _chain.get(_filter++);
                if (LOG.isDebugEnabled())
                    LOG.debug("call filter " + holder);
                Filter filter = holder.getFilter();

                //if the request already does not support async, then the setting for the filter
                //is irrelevant. However if the request supports async but this filter does not
                //temporarily turn it off for the execution of the filter
                if (!holder.isAsyncSupported() && _baseRequest.isAsyncSupported())
                {
                    try
                    {
                        _baseRequest.setAsyncSupported(false, holder.toString());
                        filter.doFilter(request, response, this);
                    }
                    finally
                    {
                        _baseRequest.setAsyncSupported(true, null);
                    }
                }
                else
                    filter.doFilter(request, response, this);

                return;
            }

            // Call servlet
            HttpServletRequest srequest = (HttpServletRequest)request;
            if (_servletHolder == null)
                notFound(Request.getBaseRequest(request), srequest, (HttpServletResponse)response);
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("call servlet {}", _servletHolder);
                _servletHolder.handle(_baseRequest, request, response);
            }
        }

        @Override
        public String toString()
        {
            StringBuilder b = new StringBuilder();
            for (FilterHolder f : _chain)
            {
                b.append(f.toString());
                b.append("->");
            }
            b.append(_servletHolder);
            return b.toString();
        }
    }

    /**
     * @return The maximum entries in a filter chain cache.
     */
    public int getMaxFilterChainsCacheSize()
    {
        return _maxFilterChainsCacheSize;
    }

    /**
     * Set the maximum filter chain cache size.
     * Filter chains are cached if {@link #isFilterChainsCached()} is true. If the max cache size
     * is greater than zero, then the cache is flushed whenever it grows to be this size.
     *
     * @param maxFilterChainsCacheSize the maximum number of entries in a filter chain cache.
     */
    public void setMaxFilterChainsCacheSize(int maxFilterChainsCacheSize)
    {
        _maxFilterChainsCacheSize = maxFilterChainsCacheSize;
    }

    void destroyServlet(Servlet servlet)
    {
        if (_contextHandler != null)
            _contextHandler.destroyServlet(servlet);
    }

    void destroyFilter(Filter filter)
    {
        if (_contextHandler != null)
            _contextHandler.destroyFilter(filter);
    }

    void destroyListener(EventListener listener)
    {
        if (_contextHandler != null)
            _contextHandler.destroyListener(listener);
    }

    /**
     * A mapping of a servlet by pathSpec or by name
     */
    public static class MappedServlet
    {
        private final PathSpec _pathSpec;
        private final ServletHolder _servletHolder;
        private final ServletPathMapping _servletPathMapping;

        MappedServlet(PathSpec pathSpec, ServletHolder servletHolder)
        {
            _pathSpec = pathSpec;
            _servletHolder = servletHolder;

            // Create the HttpServletMapping only once if possible.
            if (pathSpec instanceof ServletPathSpec)
            {
                switch (pathSpec.getGroup())
                {
                    case EXACT:
                    case ROOT:
                        _servletPathMapping = new ServletPathMapping(_pathSpec, _servletHolder.getName(), _pathSpec.getPrefix());
                        break;
                    default:
                        _servletPathMapping = null;
                        break;
                }
            }
            else
            {
                _servletPathMapping = null;
            }
        }

        public PathSpec getPathSpec()
        {
            return _pathSpec;
        }

        public ServletHolder getServletHolder()
        {
            return _servletHolder;
        }

        public ServletPathMapping getServletPathMapping(String pathInContext)
        {
            if (_servletPathMapping != null)
                return _servletPathMapping;
            if (_pathSpec != null)
                return new ServletPathMapping(_pathSpec, _servletHolder.getName(), pathInContext);
            return null;
        }

        @Override
        public String toString()
        {
            return String.format("MappedServlet%x{%s->%s}",
                hashCode(), _pathSpec == null ? null : _pathSpec.getDeclaration(), _servletHolder);
        }

    }

    @SuppressWarnings("serial")
    public static class Default404Servlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException
        {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
        }
    }
}
