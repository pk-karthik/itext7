package com.itextpdf.core.events;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class EventDispatcher implements IEventDispatcher {

    protected HashMap<String, ArrayList<IEventHandler>> eventHandlers = new HashMap<String, ArrayList<IEventHandler>>();
    protected List<Event> delayedEvents = new ArrayList<Event>();

    @Override
    public void addEventHandler(String type, IEventHandler handler) {
        removeEventHandler(type, handler);
        ArrayList<IEventHandler> handlers = eventHandlers.get(type);
        if (handlers == null) {
            handlers = new ArrayList<IEventHandler>();
            eventHandlers.put(type, handlers);
        }
        handlers.add(handler);
        for (Event event : delayedEvents) {
            if (event.getType() == type) {
                delayedEvents.remove(event);
                dispatchEvent(event);
            }
        }
    }

    @Override
    public void dispatchEvent(Event event) {
        dispatchEvent(event, false);
    }

    @Override
    public void dispatchEvent(Event event, boolean delayed) {
        if (delayed)
            delayedEvents.add(event);
        else {
            ArrayList<IEventHandler> handlers = eventHandlers.get(event.getType());
            if (handlers != null) {
                for (IEventHandler handler : handlers) {
                    handler.handleEvent(event);
                }
            }
        }
    }

    @Override
    public boolean hasEventHandler(String type) {
        return eventHandlers.containsKey(type);
    }

    @Override
    public void removeEventHandler(String type, IEventHandler handler) {
        ArrayList<IEventHandler> handlers = eventHandlers.get(type);
        if (handlers == null)
            return;
        handlers.remove(handler);
        if (handlers.size() == 0)
            eventHandlers.remove(type);
    }

    @Override
    public void removeAllHandlers() {
        eventHandlers.clear();
        delayedEvents.clear();
    }


}