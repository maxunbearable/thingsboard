///
/// Copyright © 2016-2025 The Thingsboard Authors
///
/// Licensed under the Apache License, Version 2.0 (the "License");
/// you may not use this file except in compliance with the License.
/// You may obtain a copy of the License at
///
///     http://www.apache.org/licenses/LICENSE-2.0
///
/// Unless required by applicable law or agreed to in writing, software
/// distributed under the License is distributed on an "AS IS" BASIS,
/// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
/// See the License for the specific language governing permissions and
/// limitations under the License.
///

import {
  CirclesDataLayerSettings,
  defaultBaseCirclesDataLayerSettings,
  isJSON,
  TbCircleData,
  TbMapDatasource
} from '@home/components/widget/lib/maps/models/map.models';
import L from 'leaflet';
import { FormattedData } from '@shared/models/widget.models';
import { TbShapesDataLayer } from '@home/components/widget/lib/maps/data-layer/shapes-data-layer';
import { TbMap } from '@home/components/widget/lib/maps/map';
import { Observable } from 'rxjs';
import { isNotEmptyStr } from '@core/utils';
import {
  MapDataLayerType,
  TbDataLayerItem,
  UnplacedMapDataItem
} from '@home/components/widget/lib/maps/data-layer/map-data-layer';
import { map } from 'rxjs/operators';

class TbCircleDataLayerItem extends TbDataLayerItem<CirclesDataLayerSettings, TbCirclesDataLayer> {

  private circle: L.Circle;
  private circleStyle: L.PathOptions;
  private editing = false;

  constructor(data: FormattedData<TbMapDatasource>,
              dsData: FormattedData<TbMapDatasource>[],
              protected settings: CirclesDataLayerSettings,
              protected dataLayer: TbCirclesDataLayer) {
    super(data, dsData, settings, dataLayer);
  }

  protected create(data: FormattedData<TbMapDatasource>, dsData: FormattedData<TbMapDatasource>[]): L.Layer {
    const circleData = this.dataLayer.extractCircleCoordinates(data);
    const center = new L.LatLng(circleData.latitude, circleData.longitude);
    this.circleStyle = this.dataLayer.getShapeStyle(data, dsData);
    this.circle = L.circle(center, {
      bubblingMouseEvents: !this.dataLayer.isEditMode(),
      radius: circleData.radius,
      ...this.circleStyle,
      snapIgnore: !this.dataLayer.isSnappable()
    });
    this.updateLabel(data, dsData);
    return this.circle;
  }

  protected unbindLabel() {
    this.circle.unbindTooltip();
  }

  protected bindLabel(content: L.Content): void {
    this.circle.bindTooltip(content, { className: 'tb-circle-label', permanent: true, direction: 'center'})
    .openTooltip(this.circle.getLatLng());
  }

  protected doUpdate(data: FormattedData<TbMapDatasource>, dsData: FormattedData<TbMapDatasource>[]): void {
    this.circleStyle = this.dataLayer.getShapeStyle(data, dsData);
    this.updateCircleShape(data);
    this.updateTooltip(data, dsData);
    this.updateLabel(data, dsData);
    this.circle.setStyle(this.circleStyle);
  }

  protected doInvalidateCoordinates(data: FormattedData<TbMapDatasource>, _dsData: FormattedData<TbMapDatasource>[]): void {
    this.updateCircleShape(data);
  }

  protected addItemClass(clazz: string): void {
    if ((this.circle as any)._path) {
      L.DomUtil.addClass((this.circle as any)._path, clazz);
    }
  }

  protected removeItemClass(clazz: string): void {
    if ((this.circle as any)._path) {
      L.DomUtil.removeClass((this.circle as any)._path, clazz);
    }
  }

  protected enableDrag(): void {
    this.circle.pm.setOptions({
      snappable: this.dataLayer.isSnappable()
    });
    this.circle.pm.enableLayerDrag();
    this.circle.on('pm:dragstart', () => {
      this.editing = true;
    });
    this.circle.on('pm:dragend', () => {
      this.saveCircleCoordinates();
      this.editing = false;
    });
  }

  protected disableDrag(): void {
    this.circle.pm.disableLayerDrag();
    this.circle.off('pm:dragstart');
    this.circle.off('pm:dragend');
  }

  protected onSelected(): L.TB.ToolbarButtonOptions[] {
    if (this.dataLayer.isEditEnabled()) {
      this.circle.on('pm:markerdragstart', () => this.editing = true);
      this.circle.on('pm:markerdragend', () => this.editing = false);
      this.circle.on('pm:edit', () => this.saveCircleCoordinates());
      this.circle.pm.enable();
    }
    return [];
  }

  protected onDeselected(): void {
    if (this.dataLayer.isEditEnabled()) {
      this.circle.pm.disable();
      this.circle.off('pm:markerdragstart');
      this.circle.off('pm:markerdragend');
      this.circle.off('pm:edit');
    }
  }

  protected removeDataItemTitle(): string {
    return this.dataLayer.getCtx().translate.instant('widgets.maps.data-layer.circle.remove-circle-for', {entityName: this.data.entityName});
  }

  protected removeDataItem(): Observable<any> {
    return this.dataLayer.saveCircleCoordinates(this.data, null, null);
  }

  public isEditing() {
    return this.editing;
  }

  public updateBubblingMouseEvents() {
    this.circle.options.bubblingMouseEvents =  !this.dataLayer.isEditMode();
  }

  private saveCircleCoordinates() {
    const center = this.circle.getLatLng();
    const radius = this.circle.getRadius();
    this.dataLayer.saveCircleCoordinates(this.data, center, radius).subscribe();
  }

  private updateCircleShape(data: FormattedData<TbMapDatasource>) {
    if (this.editing) {
      return;
    }
    const circleData = this.dataLayer.extractCircleCoordinates(data);
    const center = new L.LatLng(circleData.latitude, circleData.longitude);
    if (!this.circle.getLatLng().equals(center)) {
      this.circle.setLatLng(center);
    }
    if (this.circle.getRadius() !== circleData.radius) {
      this.circle.setRadius(circleData.radius);
    }
  }
}

export class TbCirclesDataLayer extends TbShapesDataLayer<CirclesDataLayerSettings, TbCirclesDataLayer> {

  constructor(protected map: TbMap<any>,
              inputSettings: CirclesDataLayerSettings) {
    super(map, inputSettings);
  }

  public dataLayerType(): MapDataLayerType {
    return MapDataLayerType.circle;
  }

  public placeItem(item: UnplacedMapDataItem, layer: L.Layer): void {
    if (layer instanceof L.Circle) {
      const center = layer.getLatLng();
      const radius = layer.getRadius();
      this.saveCircleCoordinates(item.entity, center, radius).subscribe(
        (converted) => {
          item.entity[this.settings.circleKey.label] = JSON.stringify(converted);
          this.createItemFromUnplaced(item);
        }
      );
    } else {
      console.warn('Unable to place item, layer is not a circle.');
    }
  }

  protected setupDatasource(datasource: TbMapDatasource): TbMapDatasource {
    datasource.dataKeys.push(this.settings.circleKey);
    return datasource;
  }

  protected defaultBaseSettings(map: TbMap<any>): Partial<CirclesDataLayerSettings> {
    return defaultBaseCirclesDataLayerSettings(map.type());
  }

  protected doSetup(): Observable<void> {
    return super.doSetup();
  }

  protected isValidLayerData(layerData: FormattedData<TbMapDatasource>): boolean {
    return layerData && isNotEmptyStr(layerData[this.settings.circleKey.label]) && isJSON(layerData[this.settings.circleKey.label]);
  }

  protected createLayerItem(data: FormattedData<TbMapDatasource>, dsData: FormattedData<TbMapDatasource>[]): TbDataLayerItem<CirclesDataLayerSettings, TbCirclesDataLayer> {
    return new TbCircleDataLayerItem(data, dsData, this.settings, this);
  }

  public extractCircleCoordinates(data: FormattedData<TbMapDatasource>) {
    const circleData: TbCircleData = JSON.parse(data[this.settings.circleKey.label]);
    return this.map.circleDataToCoordinates(circleData);
  }

  public saveCircleCoordinates(data: FormattedData<TbMapDatasource>, center: L.LatLng, radius: number): Observable<TbCircleData> {
    const converted = center ? this.map.coordinatesToCircleData(center, radius) : null;
    const circleData = [
      {
        dataKey: this.settings.circleKey,
        value: converted
      }
    ];
   return this.map.saveItemData(data.$datasource, circleData).pipe(
     map(() => converted)
   );
  }
}
