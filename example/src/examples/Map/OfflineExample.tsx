import Mapbox, {
  Camera,
  Logger,
  MapView,
  offlineManager,
} from '@rnmapbox/maps';
import React, { useLayoutEffect, useState } from 'react';
import { Button, TextInput } from 'react-native';

import Page from '../common/Page';
import { BaseExampleProps } from '../common/BaseExamplePropTypes';

const CENTER_COORD: [number, number] = [10.60776473348605, 59.874330907868426];
const STYLE_URL = Mapbox.StyleURL.Light;

const OfflineExample = (props: BaseExampleProps) => {
  const [packName, setPackName] = useState('pack-1');
  const [showEditTitle, setShowEditTitle] = useState(false);

  useLayoutEffect(() => {
    Logger.setLogCallback(() => {
      return true;
    });
  }, []);

  return (
    <Page {...props}>
      <Button
        title={`Pack name: ${packName}`}
        onPress={() => {
          setShowEditTitle(!showEditTitle);
        }}
      />
      {showEditTitle && (
        <TextInput
          value={packName}
          autoFocus={true}
          onChangeText={(text) => setPackName(text)}
          onBlur={() => setShowEditTitle(false)}
        />
      )}
      <Button
        title="Get all packs"
        onPress={async () => {
          try {
            const packs = await offlineManager.getPacks();
            console.log('packs', packs);
            packs.forEach((pack) => {
              console.log('name:', pack);
            });
          } catch (error) {
            console.log('getPacks', error);
          }
        }}
      />
      <Button
        title="Get pack"
        onPress={async () => {
          const pack = await offlineManager.getPack(packName);
          if (pack) {
            console.log(
              'pack:',
              pack,
              'name:',
              pack.name,
              'bounds:',
              pack?.bounds,
              'metadata',
              pack?.metadata,
            );

            console.log('=> status', await pack?.status());
            console.log('time', new Date());
          }
        }}
      />
      <Button
        title="Resume pack"
        onPress={async () => {
          const pack = await offlineManager.getPack(packName);
          if (pack) {
            await pack.resume();
          }
        }}
      />
      <Button
        title="Remove packs"
        onPress={async () => {
          const packs = await offlineManager.getPacks();
          for (const pack of packs) {
            const result = await offlineManager.deletePack(pack.name);
            console.log('Pack deleted:', result);
          }
          console.log('Reset DB done');
        }}
      />
      <Button
        title="Create Pack"
        onPress={async () => {
          const options = {
            name: packName,
            styleURL: STYLE_URL,
            bounds: [
              [10.60776473348605, 59.874330907868426],
              [10.712617871809584, 59.92020667067757],
            ] as [[number, number], [number, number]],
            minZoom: 4,
            maxZoom: 16,
            metadata: {
              whatIsThat: 'foo',
            },
          };

          try {
            await offlineManager.createPack(options, (region, status) =>
              console.log(
                '=> progress callback region:',
                props,
                'status: ',
                status,
              ),
            );
            console.log('createPack: done');
          } catch (error) {
            console.log('createPack: error', error);
          }
        }}
      />
      <Button
        title="Pause pack"
        onPress={async () => {
          const pack = await offlineManager.getPack(packName);
          if (pack) {
            console.log('name:!!', pack);
            console.log('=> pause', await pack?.pause());
          }
        }}
      />
      <Button
        title="Resume pack"
        onPress={async () => {
          const pack = await offlineManager.getPack(packName);
          if (pack) {
            console.log('name:', pack.name);
            console.log('=> resume', await pack?.resume());
          }
        }}
      />
      <MapView style={{ flex: 1 }} styleURL={STYLE_URL}>
        <Camera zoomLevel={10} centerCoordinate={CENTER_COORD} />
      </MapView>
    </Page>
  );
};

export default OfflineExample;
