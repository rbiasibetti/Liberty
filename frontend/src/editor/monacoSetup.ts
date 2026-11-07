import { loader } from '@monaco-editor/react';
import * as monaco from 'monaco-editor';

/**
 * O Monaco vem do pacote local (nada de CDN: o CSP da aplicação só aceita a própria origem). As
 * versões atuais do pacote criam os próprios workers como arquivos do build, da mesma origem.
 */
loader.config({ monaco });

export { monaco };
