/**
 * Copyright 2010 ArcBees Inc.
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

package com.gwtplatform.mvp.client.gwt.mvp;

import com.google.gwt.event.shared.EventBus;
import com.google.gwt.inject.client.GinModules;
import com.google.gwt.inject.client.Ginjector;
import com.google.inject.Provider;
import com.gwtplatform.mvp.client.proxy.PlaceManager;
import com.gwtplatform.mvp.client.proxy.ProxyFailureHandler;

/**
 * @author Philippe Beaudoin
 */
@GinModules({ClientModuleTestUtilGwt.class})
public interface GinjectorTestUtilGwt extends Ginjector {
  Provider<MainPresenterTestUtilGwt> getMainPresenter();
  Provider<AdminPresenterTestUtilGwt> getAdminPresenter();
  EventBus getEventBus();
  PlaceManager getPlaceManager();
  ProxyFailureHandler getProxyFailureHandler();
  InjectedLabel getInjectedLabel();
}