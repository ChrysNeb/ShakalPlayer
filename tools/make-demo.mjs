import { mkdirSync, writeFileSync } from 'node:fs';

const rate = 44100;
const stepSeconds = 60 / 154 / 2;
const tau = 2 * Math.PI;
const notes = [36, 36, 43, 36, 39, 36, 46, 43, 36, 48, 43, 36, 39, 46, 41, 34];
const frames = Math.round(64 * stepSeconds * rate);
const pcm = Buffer.alloc(44 + frames * 4);
const pitch = midi => 440 * 2 ** ((midi - 69) / 12);

pcm.write('RIFF', 0);
pcm.writeUInt32LE(pcm.length - 8, 4);
pcm.write('WAVEfmt ', 8);
pcm.writeUInt32LE(16, 16);
pcm.writeUInt16LE(1, 20);
pcm.writeUInt16LE(2, 22);
pcm.writeUInt32LE(rate, 24);
pcm.writeUInt32LE(rate * 4, 28);
pcm.writeUInt16LE(4, 32);
pcm.writeUInt16LE(16, 34);
pcm.write('data', 36);
pcm.writeUInt32LE(frames * 4, 40);

let random = 81;
for (let i = 0; i < frames; i++) {
    const time = i / rate;
    const step = Math.floor(time / stepSeconds);
    const x = time - step * stepSeconds;
    const beat = x + stepSeconds * (step % 2);
    const kickPhase = tau * (52 * beat + 4 * (1 - Math.exp(-beat * 50)));
    const kick = Math.tanh(2.8 * Math.sin(kickPhase)) * Math.exp(-15 * beat);
    const bassPhase = tau * pitch(notes[step % notes.length]) * x;
    const bass = (Math.sin(bassPhase) + 0.36 * Math.sin(3 * bassPhase)) * Math.exp(-8 * x);
    random = (Math.imul(1664525, random) + 1013904223) >>> 0;
    const hats = (random / 2147483648 - 1) * Math.exp(-56 * x) * (step % 2 ? 0.17 : 0.055);
    const tone = bassPhase * 4;
    const stab = Math.min(1, x * 300) * Math.exp(-17 * x) * (step % 2 ? 0.8 : 0.3);
    const center = 0.44 * kick + 0.23 * bass + hats + 0.17 * Math.sin(tone) * stab;
    const side = 0.10 * (Math.sin(tone * 2) - Math.sin(tone * 2.016)) * stab;
    const fade = Math.min(1, time / 0.006, (frames - i) / rate / 0.05);
    pcm.writeInt16LE(Math.round((center + side) * 30000 * fade), 44 + i * 4);
    pcm.writeInt16LE(Math.round((center - side) * 30000 * fade), 46 + i * 4);
}

const assets = new URL('../app/src/main/assets/', import.meta.url);
mkdirSync(assets, { recursive: true });
writeFileSync(new URL('demo.wav', assets), pcm);
