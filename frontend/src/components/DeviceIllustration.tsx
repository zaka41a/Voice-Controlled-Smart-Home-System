import { useId } from 'react';
import type { DeviceType } from '../types';

export function DeviceIllustration({
  type,
  active = false,
}: {
  type: DeviceType;
  active?: boolean;
}) {
  const id = useId().replace(/:/g, '');
  return (
    <svg
      className={`device-illustration ${active ? 'is-on' : ''}`}
      viewBox="0 0 280 200"
      role="img"
      aria-label={
        type === 'LIGHT'
          ? 'Light illustration'
          : type === 'PLUG'
            ? 'Smart plug illustration'
            : type === 'DOOR'
              ? 'Door illustration'
              : 'Window illustration'
      }
    >
      <defs>
        <linearGradient id={`${id}-body`} x1="0" y1="0" x2="1" y2=".7">
          <stop offset="0" stopColor="#fff" />
          <stop offset=".48" stopColor="#f0f2f5" />
          <stop offset="1" stopColor="#cdd3dc" />
        </linearGradient>
        <linearGradient id={`${id}-metal`} x1="0" y1="0" x2="1" y2="0">
          <stop stopColor="#929caa" />
          <stop offset=".4" stopColor="#e2e7ed" />
          <stop offset="1" stopColor="#8b96a6" />
        </linearGradient>
        <radialGradient id={`${id}-glow`}>
          <stop stopColor={active ? '#c6e0ff' : '#e9edf2'} stopOpacity=".95" />
          <stop offset="1" stopColor={active ? '#e6f2ff' : '#f5f7fa'} stopOpacity="0" />
        </radialGradient>
        <filter id={`${id}-shadow`} x="-50%" y="-100%" width="200%" height="300%">
          <feGaussianBlur stdDeviation="5" />
        </filter>
      </defs>
      <ellipse cx="140" cy="97" rx="111" ry="90" fill={`url(#${id}-glow)`} />
      <ellipse
        cx="140"
        cy="178"
        rx="48"
        ry="7"
        fill="#536780"
        opacity=".15"
        filter={`url(#${id}-shadow)`}
      />
      {type === 'LIGHT' ? (
        <g transform="rotate(-14 140 100)">
          <path
            d="M110 126C109 110 95 99 95 76a45 45 0 0 1 90 0c0 23-14 34-15 50Z"
            fill={`url(#${id}-body)`}
            stroke="#cbd4df"
            strokeWidth="1.2"
          />
          <path
            d="M99 79c4-23 18-36 41-38"
            fill="none"
            stroke="#fff"
            strokeWidth="7"
            strokeLinecap="round"
            opacity=".85"
          />
          <path d="M111 122h58l-7 22h-44Z" fill="#e0e6ed" />
          <path d="M117 141h46v19l-12 10h-22l-12-10Z" fill={`url(#${id}-metal)`} stroke="#97a3b2" />
          <path
            d="m118 146 44-3m-44 10 44-3m-42 10 39-3"
            fill="none"
            stroke="#8693a4"
            strokeWidth="2.8"
          />
          <path d="M132 168h16l-3 6h-10Z" fill="#4a5564" />
          <path
            d="M120 116h40"
            stroke={active ? '#007BFF' : '#b0bdcc'}
            strokeWidth="3"
            strokeLinecap="round"
          />
        </g>
      ) : type === 'PLUG' ? (
        <g transform="rotate(-9 140 110)">
          <path d="M130 39v-17m25 17v-17" stroke="#95a0ad" strokeWidth="8" strokeLinecap="round" />
          <path
            d="M109 36h55a21 21 0 0 1 21 21v86a22 22 0 0 1-22 22h-55a22 22 0 0 1-22-22V59a23 23 0 0 1 23-23Z"
            fill="#c7cfd9"
          />
          <rect
            x="87"
            y="40"
            width="91"
            height="123"
            rx="24"
            fill={`url(#${id}-body)`}
            stroke="#cbd4df"
          />
          <circle cx="133" cy="94" r="30" fill="#d9e0e8" stroke="#c2cbd7" />
          <circle cx="133" cy="94" r="25" fill="#eef1f5" />
          <circle cx="122" cy="94" r="4" fill="#657184" />
          <circle cx="144" cy="94" r="4" fill="#657184" />
          <path d="M133 68v7m0 38v7" stroke="#99a5b5" strokeWidth="3" />
          <rect
            x="124"
            y="139"
            width="18"
            height="4"
            rx="2"
            fill={active ? '#007BFF' : '#acb9c9'}
          />
        </g>
      ) : type === 'DOOR' ? (
        <g>
          <path d="M91 177V28h94v149" fill="#d4dce6" stroke="#b3bfce" strokeWidth="2" />
          <path d="M98 177V35h80v142" fill={active ? '#60758e' : '#f2f5f8'} />
          {active ? (
            <>
              <path d="m99 35 60 13v139l-60-11Z" fill={`url(#${id}-body)`} stroke="#aebdcc" />
              <path d="m111 54 35 8v53l-35-8Zm0 70 35 8v34l-35-8Z" fill="none" stroke="#c2ceda" />
              <path d="m143 127 12 3" stroke="#5e6b7c" strokeWidth="4" strokeLinecap="round" />
            </>
          ) : (
            <>
              <rect x="101" y="39" width="74" height="136" fill={`url(#${id}-body)`} />
              <rect x="113" y="53" width="50" height="51" rx="1" fill="none" stroke="#c6d0dc" />
              <rect x="113" y="123" width="50" height="37" rx="1" fill="none" stroke="#c6d0dc" />
              <path d="M155 113h12" stroke="#607083" strokeWidth="4" strokeLinecap="round" />
            </>
          )}
          <path d="M86 178h104" stroke="#a3b2c4" strokeWidth="3" strokeLinecap="round" />
        </g>
      ) : (
        <g>
          <rect
            x="75"
            y="37"
            width="124"
            height="130"
            rx="3"
            fill={`url(#${id}-body)`}
            stroke="#b8c4d3"
            strokeWidth="2"
          />
          <rect x="83" y="45" width="108" height="114" fill="#dcebf8" stroke="#bdcddb" />
          <path d="M137 43v118M82 102h109" stroke="#f8fafc" strokeWidth="7" />
          <path
            d="m89 86 36-34m-36 88 29-28m30-25 35-34m-35 88 34-32"
            stroke="#fff"
            strokeWidth="3"
            opacity=".65"
          />
          {active && (
            <>
              <path d="m139 46 35-13v136l-35-11Z" fill="#c9e2fb" stroke="#8baed3" strokeWidth="3" />
              <path d="m141 102 31-2" stroke="#eef5fd" strokeWidth="5" />
              <path d="m166 98 0 13" stroke="#607b98" strokeWidth="3" strokeLinecap="round" />
            </>
          )}
          {!active && (
            <path d="M130 96v15m14-15v15" stroke="#8399b2" strokeWidth="3" strokeLinecap="round" />
          )}
          <path d="M69 170h136" stroke="#adbccd" strokeWidth="5" strokeLinecap="round" />
        </g>
      )}
    </svg>
  );
}
